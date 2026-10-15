package coupledMCMC;




// Publicly exported by the declared beast-base 2.8.0-beta5 module.
import beast.base.internal.json.JSONObject;
import beast.base.internal.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import beast.base.core.Citation.Citations;
import beast.base.inference.Logger;
import beast.base.inference.MCMC;
import beast.base.inference.Operator;
import beast.base.core.Citation;
import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.core.Log;
import beast.base.util.HeapSort;
import beast.base.util.Randomizer;
import beast.base.parser.XMLParser;
import beast.base.parser.XMLProducer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Citations(
		{
		@Citation(value= "Müller, Nicola Felix, and Remco Bouckaert. Adaptive Metropolis-coupled MCMC for BEAST 2.\n" + 
				"PeerJ 8:e9473 ", 
				year = 2020, firstAuthorSurname = "Müller",
				DOI="10.7717/peerj.9473"),
		@Citation(value="Bouckaert, Remco, Timothy G. Vaughan, Joëlle Barido-Sottani, Sebastián Duchêne, \n"
				+ "  Mathieu Fourment, Alexandra Gavryushkina, Joseph Heled et al. \n"
				+ "  BEAST 2.5: An advanced software platform for Bayesian evolutionary analysis. \n"
				+ "  PLoS computational biology 15, no. 4 (2019): e1006650.", 
		        year = 2019, firstAuthorSurname = "bouckaert",
				DOI="10.1371/journal.pcbi.1006650"),	
		@Citation(value= "Altekar G, Dwarkadas S, Huelsenbeck J and Ronquist F (2004). \n" +
				"  Parallel Metropolis Coupled Markov Chain Monte Carlo For Bayesian Phylogenetic Inference.\n" +
				"  Bioinformatics, 20(3), 407-415."
				, year = 2004, firstAuthorSurname = "Altekar",
				DOI="10.1093/bioinformatics/btg427")
		}
)		
@Description("Parallel Metropolis Coupled Markov Chain Monte Carlo")
public class CoupledMCMC extends MCMC {
	public Input<Integer> nrOfChainsInput = new Input<Integer>("chains", " number of chains to run in parallel", 2);
	public Input<Integer> resampleEveryInput = new Input<Integer>("resampleEvery", "number of samples in between resampling (and possibly swappping) states", 100);
	public Input<String> tempDirInput = new Input<>("tempDir","directory where temporary files are written","");
	
	// input of the difference between temperature scalers
	public Input<Double> deltaTemperatureInput = new Input<>("deltaTemperature","nonnegative finite spacing parameter; adjacent temperature difference in geometric mode", 0.1);
	public Input<Double> maxTemperatureInput = new Input<>("maxTemperature","optional finite nonnegative hottest-chain maximum, enforced from initialization even when optimise=false");
	public Input<Boolean> logHeatedChainsInput = new Input<>("logHeatedChains","if true, log files for heated chains are also printed", false);
	
	public Input<Double> maxAdaptationInput = new Input<>("maxAdaptation","maximal step size for adapting the temperature", 0.001);	

	// Input on whether the temperature between chains should be optimized
	public Input<Boolean> optimiseInput = new Input<>("optimise","if true, the temperature is automatically optimised to reach a target acceptance probability", true);
	
	public Input<Integer> optimiseDelayInput = new Input<>("optimiseDelay","after this many epochs/swaps, the temperature will be optimized (if optimising is set to true)", 100);
	public Input<Double> targetAcceptanceProbabilityInput = new Input<>("target", "target acceptance probability of swaps", 0.234);
	
	public Input<Boolean> preScheduleInput = new Input<>("preSchedule","if true, how long chains are run for is scheduled at the beginning", true);
	public Input<Boolean> neighbourSwappingInput = new Input<>("neighbourSwapping","if true, only chains that are next to each other are swapped", false);

	public Input<Boolean> useBetaDistributionInput = new Input<>("useBetaDistribution","if true, use Beta(1, deltaTemperature) spacing; zero spacing means no heating", false);

	public Input<Boolean> heatLikelihoodOnlyInput = new Input<>("heatLikelihoodOnly", "if true, samples log(prior) + beta * log(likelihood) instead of the default beta * (log(prior) + log(likelihood))", false);

	// nr of samples between re-arranging states
	int resampleEvery;	
	
	double deltaTemperature;
	
	private boolean optimise;
	
	/** plugins representing MCMC with model, loggers, etc **/
	HeatedChain [] chains;
	
	/** threads for running MCMC chains **/
	Thread [] threads;
	
	
	/** keep track of time taken between logs to estimate speed **/
    long startLogTime;
    
    /** keeps track of the last n swaps and if they were accepted or not **/
    List<Boolean> acceptedSwaps;

	// keep track of when threads finish in order to optimise thread usage
	long [] finishTimes;
		
	private ArrayList<Long>[] runTillIteration;
	
	int totalSwaps = 0;
	int successfullSwaps = 0, successfullSwaps0 = 0;
	
	long sampleOffset = 0;
	
	// defines which scheme for spacing between the heated chains to use
	private enum Spacing{Geometric, Beta };
	private Spacing spacing;

	@Override
	public void initAndValidate() {
		if (nrOfChainsInput.get() < 1) {
			throw new RuntimeException("chains must be at least 1");
		}
		if (nrOfChainsInput.get() == 1) {
			Log.warning.println("Warning: coupled MCMC needs at least 2 chains, but the number of chains is 1. Running plain MCMC.");
			super.initAndValidate();
			return;
		}
		validateConfiguredTemperatureInputs();
		// initialize the differently heated chains
		chains = new HeatedChain[nrOfChainsInput.get()];
		
		resampleEvery = resampleEveryInput.get();
        if (resampleEvery <= 0) throw new IllegalArgumentException("resampleEvery must be positive");
		
		spacing = useBetaDistributionInput.get() ? Spacing.Beta : Spacing.Geometric;
		deltaTemperature = applySpacingBounds(deltaTemperatureInput.get());
		
		acceptedSwaps = new ArrayList<>();				
		
		optimise = optimiseInput.get();
		
				
	} // initAndValidate
	
    private static String workingStateFileName(String stateFileName, int physical) {
        String base = Path.of(stateFileName).getFileName().toString();
        String marker = "." + physical + "state";
        return base.endsWith(".state")
                ? base.substring(0, base.length() - ".state".length()) + marker
                : base + marker;
    }

	private String createChainXML(){
		XMLProducer p = new XMLProducer();
		List<CoupledLogger> coupledLoggers = new ArrayList<>();
		for (Logger logger : loggersInput.get()) {
			if (logger.fileNameInput.get() != null) {
				CoupledLogger coupledLogger = new CoupledLogger(logger);
				coupledLoggers.add(coupledLogger);
			}
			
		}
		
		HeatedChain heated = heatLikelihoodOnlyInput.get() ? new HeatedChainLikelihoodOnly() : new HeatedChain();
		if (initialisersInput.get().size()==0)
			heated.initByName(
					"distribution", posteriorInput.get(), 
					"operator", operatorsInput.get(),
					"state", startStateInput.get(),
					"chainLength", chainLengthInput.get(),
					"storeEvery", storeEveryInput.get(),
					"numInitializationAttempts", numInitializationAttempts.get(),
					"coupledLogger", coupledLoggers
					);
		else
			heated.initByName(
					"distribution", posteriorInput.get(), 
					"operator", operatorsInput.get(),
					"state", startStateInput.get(),
					"init", initialisersInput.get(),
					"chainLength", chainLengthInput.get(),
					"storeEvery", storeEveryInput.get(),
					"numInitializationAttempts", numInitializationAttempts.get(),
					"coupledLogger", coupledLoggers
					);

        return p.toXML(heated, new ArrayList<>());
    }

    private void initRun() {
        initRun(createChainXML());
    }

    private void initRun(String sXML) {

//		String sXML = p.toXML(this, new ArrayList<>());
//		
//		// removes coupled MCMC parts of the xml		
//		sXML = sXML.replaceFirst("chains=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("resampleEvery=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("tempDir=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("deltaTemperature=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("maxTemperature=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("optimise=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("logHeatedChains=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("optimizeDelay=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("optimizeEvery=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("nrExchanges=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("preSchedule=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("target=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("neighbourSwapping=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("heatLikelihoodOnly=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceFirst("useBetaDistribution=['\"][^ ]*['\"]", "");
//		sXML = sXML.replaceAll("spec=\"Logger\"", "");
//		sXML = sXML.replaceAll("<logger", "<coupledLogger spec=\"beast.coupledMCMC.CoupledLogger\"");
//		sXML = sXML.replaceAll("</logger", "</coupledLogger");
		
			
		// create new chains		
		for (int i = 0; i < chains.length; i++) {
			XMLParser parser = new XMLParser();
			String sXML2 = sXML;
			if (i>0){
				sXML2 = sXML2.replaceAll("fileName=\"", "fileName=\"chain" + i+ "");
			}
			
			try {				
				chains[i] = (HeatedChain) parser.parseFragment(sXML2, true);
	
				// remove all screen loggers
				for (int j = chains[i].coupledLoggersInput.get().size()-1; j >=0 ; j--){
					if (chains[i].coupledLoggersInput.get().get(j).getID().contentEquals("screenlog")){
						chains[i].coupledLoggersInput.get().remove(j);
					}
				}
				
				// remove all loggers of heated chains if they are not logged
				if (!logHeatedChainsInput.get() && i != 0){
					for (int j = 0; j < chains[i].coupledLoggersInput.get().size(); j++)
						chains[i].coupledLoggersInput.get().get(j).setSuppressLogging(true);					
				}
				// initialize each chain individually
				chains[i].setResampleEvery(resampleEvery);
				chains[i].setTemperature(i, getTemperature(i));
				
				// needed to avoid error of putting the working dir twice
                String workingFile = workingStateFileName(stateFileName, i);
                chains[i].setStateFile(restoreGeneration == null ? workingFile
                        : restoreGeneration.rankFiles[i].toString(), restoreFromFile);
                chains[i].setChainNr(i);
                try {
                    chains[i].run();
                } finally {
                    // Committed files are restore-only; automatic chain checkpoints remain mutable working files.
                    chains[i].setStateFileName(workingFile);
                }

			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}		
		// ensure that each chain has the same starting point
		threads = new Thread[chains.length];
		finishTimes = new long[chains.length];
		

		chainLength = chainLengthInput.get();

        verifyRankLadder();
        segmentControllerSample = 0;
        // Future schedules are process-local and regenerated on resume.
		
		// pre schedule which chains to swap when
		if (preScheduleInput.get()){
			buildSchedule();
		}
		
        if (restoreFromFile) {
            System.out.println("MC3_RESTORE absolute=" + sampleOffset + " local=0 slot="
                    + committedSlot + " sequence=" + committedSequence);
        }

		
	}
	
    private void validateConfiguredTemperatureInputs() {
        double configuredDelta = deltaTemperatureInput.get();
        if (!Double.isFinite(configuredDelta) || configuredDelta < 0.0) {
            throw new IllegalArgumentException("deltaTemperature must be finite and >= 0");
        }
        Double maximum = maxTemperatureInput.get();
        if (maximum != null && (!Double.isFinite(maximum) || maximum < 0.0)) {
            throw new IllegalArgumentException("maxTemperature must be finite and >= 0 when supplied");
        }
    }

    /** Shared initialization/adaptation bounds; a negative internal proposal clamps to zero. */
    private double applySpacingBounds(double proposedDelta) {
        double bounded = Math.max(0.0, proposedDelta);
        Double maximum = maxTemperatureInput.get();
        // One rank is already cold: neither n-1 nor log(n) is a valid cap denominator.
        if (maximum != null && chains.length > 1) {
            double maximumDelta = spacing == Spacing.Beta
                    ? Math.log1p(maximum) / Math.log(chains.length)
                    : maximum / (chains.length - 1);
            bounded = Math.min(bounded, maximumDelta);
        }
        return bounded;
    }

    private double getTemperature(int i) {
        if (spacing == Spacing.Geometric) {
            return i * deltaTemperature;
        }
        if (deltaTemperature == 0.0) {
            return 0.0;
        }
        // Beta(1,d) survival is (1-i/n)^d. Avoid CDF subtraction and reciprocal cancellation.
        double logBeta = deltaTemperature * Math.log1p(-i / (double) chains.length);
        return Math.expm1(-logBeta);
    }


    private static long nextWorkerRngExecutionId;
    private String[] workerRngNames;

    private static synchronized long allocateWorkerRngExecutionId() {
        if (nextWorkerRngExecutionId == Long.MAX_VALUE)
            throw new IllegalStateException("Worker RNG execution IDs exhausted");
        return ++nextWorkerRngExecutionId;
    }

    /**
     * Requires quiescent Randomizer users: its operator-selection lookup is unsynchronized.
     * No uncoordinated registration or global reseeding may overlap sampling.
     * Names belong to physical chains, not ranks; completed executions retain their entries.
     * The namespace is shared by this loaded CoupledMCMC definition only.
     */
    private void initializeWorkerRngNames() {
        if (threads != null) {
            for (Thread worker : threads) {
                if (worker != null && worker.isAlive())
                    throw new IllegalStateException("Cannot prepare RNG names while a retained worker is alive");
            }
        }
        workerRngNames = null;
        String prefix = "CoupledMCMC-rng-exec-" + allocateWorkerRngExecutionId();
        String[] names = new String[chains.length];
        for (int physical = 0; physical < names.length; physical++)
            names[physical] = prefix + "-physical-" + physical;

        Thread controller = Thread.currentThread();
        String originalName = controller.getName();
        try {
            // Absorb the dependency's first post-setSeed allocation; never sample under this name.
            controller.setName(prefix + "-reservation");
            Randomizer.getSeed();
            controller.setName(originalName);
            Randomizer.getSeed();
            for (String name : names) {
                controller.setName(name);
                Randomizer.getSeed();
            }
        } finally {
            controller.setName(originalName);
        }
        workerRngNames = names;
    }

    private String workerRngName(int physical) {
        if (workerRngNames == null)
            throw new IllegalStateException("Worker RNG names are not prepared");
        return workerRngNames[physical];
    }

	class HeatedChainThread extends Thread {
		final int chainNr;
		long runUntil;
        volatile Throwable failure;

		HeatedChainThread(int chainNr, long runUntil) {
            super(CoupledMCMC.this.workerRngName(chainNr));
			this.chainNr = chainNr;
			this.runUntil = runUntil;
		}		
		
		public void run() {
			try {
				finishTimes[chainNr] = chains[chainNr].runTillResample(runUntil);
            } catch (Throwable e) {
                failure = e;
            }
		}
	}
	
	@Override 
	public void run() throws IOException {
		if (nrOfChainsInput.get() == 1) {
			try {
				super.run();
			} catch (org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
				throw new IOException("Could not run ordinary MCMC", e);
			}
			return;
		}
        preflightRestart();
        String chainXML = createChainXML();
        preflightLogOutputs();
        initRun(chainXML);

        try {
            if (neighbourSwappingInput.get() || (!preScheduleInput.get() && chains.length >= 2)) {
                runRuntimeScheduled();
            } else {
                runPrescheduled();
            }
        } catch (Throwable failure) {
            cancelAndDrainWorkers(failure);
            if (failure instanceof IOException e) throw e;
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
            throw new IllegalStateException("Multi-chain controller failed", failure);
        }
	} // run
	
	private void runPrescheduled() throws IOException {
        initializeWorkerRngNames();
		// run each thread until it's next swapping time
		// start threads with individual chains here.
		threads = new Thread[chains.length];
		for (int k = 0; k < chains.length; k++) {
			
            long runk = nextPrescheduledStop(k, 0);

			threads[k] = new HeatedChainThread(k, runk);
			threads[k].start();
		}
		
		startLogTime = -1;
		long startSample = 0;
		
		// print header for system output
		System.out.println("sample\tswapsColdCain\tswapProbability\tdeltaTemperature");		
		double currProb = ((double) successfullSwaps/totalSwaps);
		if (Double.isNaN(currProb))
			currProb = 0.0;
		System.out.print("\t" + (startSample + sampleOffset) + "\t" + successfullSwaps0 + "\t" + currProb + "\t" + deltaTemperature + "\n");

		
		for (long sampleNr = resampleEvery; sampleNr <= chainLength; sampleNr += resampleEvery) {	
			
			// get the chains to swap
			int i=-1, j=-1;
			for (int k = 0; k < threads.length; k++){
				if (runTillIteration[k].size()>0){
					if (runTillIteration[k].get(0) == sampleNr){
						if (i>=0)
							j = k;
						else
							i = k;
					}
				}
			}
			
            boolean checkpoint = checkpointDue(sampleNr);
            boolean allChainBarrier = checkpoint
                    || (optimise && (long) totalSwaps + 1L > optimiseDelayInput.get());
            if (allChainBarrier) {
                joinAllChains();
                verifyStoppedAt(sampleNr);
            } else {
                joinChain(i);
                joinChain(j);
            }
			
			// ensure that i is the colder chain		
//			if (chains[i].getBeta() <  chains[j].getBeta()) {
//				int tmp = i; i = j; j = tmp;
//			}
			if (chains[i].getChainNr() > chains[j].getChainNr()) {
				int tmp = i; i = j; j = tmp;
			}
			
			
		
			
			double p1before = chains[i].getCurrentLogLikelihood();
			double p2before = chains[j].getCurrentLogLikelihood();

			// robust calculations can be extremely expensive, just calculate the new probs instead 
			//double p1after = chains[i].getUnscaledCurrentLogLikelihood() * chains[j].getBeta();
			//double p2after = chains[j].getUnscaledCurrentLogLikelihood() * chains[i].getBeta();
			double p1after = chains[i].getScaledLogLikelihood(chains[j].getBeta());
			double p2after = chains[j].getScaledLogLikelihood(chains[i].getBeta());
			
			double logAlpha = (p1after + p2after) - (p1before  + p2before);
			if (Math.exp(logAlpha) > Randomizer.nextDouble()) {

				TuningTransferPlan tuningPlan = prepareOperatorTuning(chains[i], chains[j]);

                boolean involvesColdRank = chains[i].getChainNr() == 0 || chains[j].getChainNr() == 0;

				// swap temperatures    
				double beta = chains[i].getBeta();
				chains[i].setBeta(chains[j].getBeta());						
				chains[j].setBeta(beta);
				
				// swap chain numbers
				int chainNr = chains[i].getChainNr();
				chains[i].setChainNr(chains[j].getChainNr());
				chains[j].setChainNr(chainNr);
				
				// swap loggers and the state file names
				swapLoggers(chains[i], chains[j]);				
				
				// swap Operator tuning
				tuningPlan.apply();
                successfullSwaps++;
                if (involvesColdRank) successfullSwaps0++;
				
				if (chains[j].getBeta() < chains[i].getBeta())
					throw new IllegalArgumentException("error in temperatures of chains");
				
				if (optimise) {
					acceptedSwaps.add(true);
				}
			}else {
				if (optimise) {
					acceptedSwaps.add(false);
				}
			}
			totalSwaps++;
			
			if (optimise && totalSwaps > optimiseDelayInput.get()) {
				double delta = getDelta();			
				
				deltaTemperature = applySpacingBounds(deltaTemperature + delta);

	            // figure out order of chains
	            int n = chains.length;
	            int [] order = new int[n];
	            for (int k = 0; k < n; k++) {
	            	order[k] = k;
	            }
	            double [] temp = new double[chains.length];
	            for (int k = 0; k < n; k++) {
	            	temp[k] = 1.0 - chains[k].getBeta();
	            }
	            HeapSort.sort(temp, order);
	            
	            
	            // Apply the new temperatures while all chains are stopped.
	            for (int k = 0; k < n; k++) {
	            	chains[k].setTemperature(0, getTemperature(chains[k].getChainNr()));
	            }
	            
			}

			runTillIteration[i].remove(0);
			runTillIteration[j].remove(0);
			
            segmentControllerSample = sampleNr;
            if (checkpoint) storeStateToFile(sampleNr);
            if (allChainBarrier) {
                for (int k = 0; k < chains.length; k++) resumePrescheduled(k, sampleNr);
            } else {
                resumePrescheduled(i, sampleNr);
                resumePrescheduled(j, sampleNr);
            }
			
			System.out.print("\t" + (sampleNr + sampleOffset) + "\t" + successfullSwaps0 + "\t" + ((double) successfullSwaps/totalSwaps) + "\t" + deltaTemperature + " ");
			if (startLogTime>0){			
	            final long logTime = System.currentTimeMillis();
	            final int secondsPerMSamples = (int) ((logTime - startLogTime) * 1000.0 / (sampleNr - startSample + 1.0));
	            final String timePerMSamples =
	                    (secondsPerMSamples >= 3600 ? secondsPerMSamples / 3600 + "h" : "") +
	                            (secondsPerMSamples >= 60 ? (secondsPerMSamples % 3600) / 60 + "m" : "") +
	                            (secondsPerMSamples % 60 + "s");

            
	            System.out.print(timePerMSamples + "/Msamples\n");
			}else{
	            System.out.print("--\n");
			}
			if (sampleNr>=10000 && startLogTime<0){
				startSample = sampleNr;
				startLogTime = System.currentTimeMillis();
			}


		}
		// ensure that every chains ran to the end even if it's not participating in the last swap
        joinAllChains();
		
		System.err.println("#Swap attemps = " + totalSwaps);
		System.err.println("#Successfull swaps = " + successfullSwaps);
		System.err.println("#Successfull swaps with cold chain = " + successfullSwaps0);
		// wait 5 seconds for the log to complete
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during terminal log wait", e);
		}
	}
	
	// Select physical chains in current rank order; no persistent schedule is needed.
	private int[] selectRuntimePair() {
		int i, j;
		if (neighbourSwappingInput.get()) {
			int chain_i = Randomizer.nextInt(chains.length-1);
			i = -1;
			j = -1;
			for (int k = 0; k < chains.length; k++) {
				if (chains[k].getChainNr() == chain_i)
					i = k;
				if (chains[k].getChainNr() == chain_i+1)
					j = k;
			}
		} else {
			// Same distinct-index draw law as buildSchedule().
			i = Randomizer.nextInt(chains.length);
			j = i;
			while (i == j)
				j = Randomizer.nextInt(chains.length);
			if (chains[i].getChainNr() > chains[j].getChainNr()) {
				int tmp = i;
				i = j;
				j = tmp;
			}
		}
		return new int[] {i, j};
	}

	private void runRuntimeScheduled() throws IOException {
        initializeWorkerRngNames();
		// run each thread until it's next swapping time
		// start threads with individual chains here.
		threads = new Thread[chains.length];
		for (int k = 0; k < chains.length; k++) {
			threads[k] = new HeatedChainThread(k, Math.min(resampleEvery, chainLength));
			threads[k].start();
		}
		
		startLogTime = -1;
		long startSample = 0;
		
		// print header for system output
		System.out.println("sample\tswapsColdCain\tswapProbability\tdeltaTemperature");		
		double currProb = ((double) successfullSwaps/totalSwaps);
		if (Double.isNaN(currProb))
			currProb = 0.0;
		System.out.print("\t" + (startSample + sampleOffset) + "\t" + successfullSwaps0 + "\t" + currProb + "\t" + deltaTemperature + "\n");

		
		for (long sampleNr = resampleEvery; sampleNr <= chainLength; sampleNr += resampleEvery) {	
			int[] pair = selectRuntimePair();
			int i = pair[0], j = pair[1];

            joinAllChains();

			double p1before = chains[i].getCurrentLogLikelihood();
			double p2before = chains[j].getCurrentLogLikelihood();

			// robust calculations can be extremly expensive, just calculate the new probs instead 
			//double p1after = chains[i].getUnscaledCurrentLogLikelihood() * chains[j].getBeta();
			//double p2after = chains[j].getUnscaledCurrentLogLikelihood() * chains[i].getBeta();
			double p1after = chains[i].getScaledLogLikelihood(chains[j].getBeta());
			double p2after = chains[j].getScaledLogLikelihood(chains[i].getBeta());
			
			double logAlpha = (p1after + p2after) - (p1before  + p2before);
			if (Math.exp(logAlpha) > Randomizer.nextDouble()) {
				TuningTransferPlan tuningPlan = prepareOperatorTuning(chains[i], chains[j]);

                boolean involvesColdRank = chains[i].getChainNr() == 0 || chains[j].getChainNr() == 0;

				// swap temperatures    
				double beta = chains[i].getBeta();
				chains[i].setBeta(chains[j].getBeta());						
				chains[j].setBeta(beta);
				
				// swap chain numbers
				int chainNr = chains[i].getChainNr();
				chains[i].setChainNr(chains[j].getChainNr());
				chains[j].setChainNr(chainNr);
				
				// swap loggers and the state file names
				swapLoggers(chains[i], chains[j]);				
				
				// swap Operator tuning
				tuningPlan.apply();
                successfullSwaps++;
                if (involvesColdRank) successfullSwaps0++;
				
				if (chains[j].getBeta() < chains[i].getBeta())
					throw new IllegalArgumentException("error in temperatures of chains");
				
				if (optimise) {
					acceptedSwaps.add(true);
				}
			}else {
				if (optimise) {
					acceptedSwaps.add(false);
				}
			}
			totalSwaps++;
			
			if (optimise && totalSwaps > optimiseDelayInput.get()) {
				double delta = getDelta();			
				
				deltaTemperature = applySpacingBounds(deltaTemperature + delta);

	            // figure out order of chains
	            int n = chains.length;
	            int [] order = new int[n];
	            for (int k = 0; k < n; k++) {
	            	order[k] = k;
	            }
	            double [] temp = new double[chains.length];
	            for (int k = 0; k < n; k++) {
	            	temp[k] = 1.0 - chains[k].getBeta();
	            }
	            HeapSort.sort(temp, order);
	            
	            
	            // set new temperatures asynchronously, in same order as before
	            for (int k = 0; k < n; k++) {
	            	chains[k].setTemperature(0, getTemperature(chains[k].getChainNr()));
	            }
	            
			}

            segmentControllerSample = sampleNr;
            if (checkpointDue(sampleNr)) storeStateToFile(sampleNr);

			if (sampleNr < chainLength) {
				for (int k = 0; k < chains.length; k++) {
					threads[k] = new HeatedChainThread(k, Math.min(sampleNr+resampleEvery, chainLength));
					threads[k].start();
				}
			}

			
			System.out.print("\t" + (sampleNr + sampleOffset) + "\t" + successfullSwaps0 + "\t" + ((double) successfullSwaps/totalSwaps) + "\t" + deltaTemperature + " ");
			if (startLogTime>0){			
	            final long logTime = System.currentTimeMillis();
	            final int secondsPerMSamples = (int) ((logTime - startLogTime) * 1000.0 / (sampleNr - startSample + 1.0));
	            final String timePerMSamples =
	                    (secondsPerMSamples >= 3600 ? secondsPerMSamples / 3600 + "h" : "") +
	                            (secondsPerMSamples >= 60 ? (secondsPerMSamples % 3600) / 60 + "m" : "") +
	                            (secondsPerMSamples % 60 + "s");

            
	            System.out.print(timePerMSamples + "/Msamples\n");
			}else{
	            System.out.print("--\n");
			}
			if (sampleNr>=10000 && startLogTime<0){
				startSample = sampleNr;
				startLogTime = System.currentTimeMillis();
			}


		}
		// ensure that every chains ran to the end even if it's not participating in the last swap
        joinAllChains();
		
		System.err.println("#Swap attemps = " + totalSwaps);
		System.err.println("#Successfull swaps = " + successfullSwaps);
		System.err.println("#Successfull swaps with cold chain = " + successfullSwaps0);
		// wait 5 seconds for the log to complete
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during terminal log wait", e);
		}
	}
	
	/* swaps the states of mcmc1 and mcmc2 */
	void swapLoggers(HeatedChain mcmc1, HeatedChain mcmc2) {
		int mcmc2size = mcmc2.coupledLoggersInput.get().size();
		int mcmc1size = mcmc1.coupledLoggersInput.get().size();
		
		for (int i = 0; i < mcmc2size; i++){
			for (int j = 0; j < mcmc1size; j++){
				if (mcmc2.coupledLoggersInput.get().get(i).getID().contentEquals(mcmc1.coupledLoggersInput.get().get(j).getID())){
					if (!logHeatedChainsInput.get()){
						boolean suppressLogging1 = mcmc1.coupledLoggersInput.get().get(j).getSuppressLogging();
						boolean suppressLogging2 = mcmc2.coupledLoggersInput.get().get(i).getSuppressLogging();
						
						mcmc1.coupledLoggersInput.get().get(j).setSuppressLogging(suppressLogging2);
						mcmc2.coupledLoggersInput.get().get(i).setSuppressLogging(suppressLogging1);

					}

					PrintStream printstream2 = mcmc2.coupledLoggersInput.get().get(i).getM_out();
					PrintStream printstream1 = mcmc1.coupledLoggersInput.get().get(j).getM_out();
										
					mcmc2.coupledLoggersInput.get().get(i).setM_out(printstream1);
					mcmc1.coupledLoggersInput.get().get(j).setM_out(printstream2);
				}					
			}			
		}		
				
		// swap the state file Names as well
		String stateFileName1 = mcmc1.getStateFileName();
		mcmc1.setStateFileName(mcmc2.getStateFileName());
		mcmc2.setStateFileName(stateFileName1);		
	}

	/** Transfer only the existing five-field payload of the resident active operators. */
	void swapOperatorTuning(HeatedChain mcmc1, HeatedChain mcmc2) {
		prepareOperatorTuning(mcmc1, mcmc2).apply();
	}

	private TuningTransferPlan prepareOperatorTuning(HeatedChain mcmc1, HeatedChain mcmc2) {
		String context = "operator tuning exchange " + tuningChainLabel(mcmc1) + " <-> " + tuningChainLabel(mcmc2);
		Map<String, Operator> first = activeTuningInventory(mcmc1, context + " side A");
		Map<String, Operator> second = activeTuningInventory(mcmc2, context + " side B");
		if (!first.keySet().equals(second.keySet())) {
			throw new IllegalArgumentException(context + ": unequal operator ID sets " + first.keySet() + " / " + second.keySet());
		}
		IdentityHashMap<Operator, Boolean> firstObjects = new IdentityHashMap<>();
		for (Operator operator : first.values()) firstObjects.put(operator, Boolean.TRUE);
		for (Map.Entry<String, Operator> entry : second.entrySet()) {
			Operator a = first.get(entry.getKey()), b = entry.getValue();
			if (firstObjects.containsKey(b)) {
				throw new IllegalArgumentException(context + ": cross-chain object alias for ID " + entry.getKey());
			}
			if (a.getClass() != b.getClass()) {
				throw new IllegalArgumentException(context + ": class mismatch for ID " + entry.getKey()
						+ ": " + tuningClassLabel(a) + " / " + tuningClassLabel(b));
			}
		}
		// Complete correspondence and BOTH full payloads precede every accepted-exchange write.
		List<TuningPair> pairs = new ArrayList<>();
		for (Map.Entry<String, Operator> entry : first.entrySet()) {
			String pairContext = context + " ID " + entry.getKey();
			Operator a = entry.getValue(), b = second.get(entry.getKey());
			try {
				pairs.add(new TuningPair(a, b, new TuningPayload(a), new TuningPayload(b), pairContext));
			} catch (RuntimeException e) {
				throw new IllegalStateException(pairContext + ": payload read failed before exchange mutation", e);
			}
		}
		return new TuningTransferPlan(pairs);
	}

	private String tuningChainLabel(HeatedChain chain) {
		int physical = -1;
		if (chains != null) {
			for (int i = 0; i < chains.length; i++) if (chains[i] == chain) physical = i;
		}
		return "physical=" + physical + ",rank=" + chain.getChainNr();
	}

	private static String tuningClassLabel(Operator operator) {
		Class<?> type = operator.getClass();
		return type.getName() + " [loader=" + type.getClassLoader()
				+ ", origin=" + type.getProtectionDomain().getCodeSource() + "]";
	}

	private static Map<String, Operator> activeTuningInventory(HeatedChain chain, String context) {
		if (chain.getOperatorSchedule() == null || chain.getOperatorSchedule().operators == null) {
			throw new IllegalArgumentException(context + ": unavailable active operator inventory");
		}
		List<Operator> snapshot = new ArrayList<>(chain.getOperatorSchedule().operators);
		if (snapshot.isEmpty()) throw new IllegalArgumentException(context + ": empty active operator inventory");
		IdentityHashMap<Operator, Boolean> seen = new IdentityHashMap<>();
		Map<String, Operator> byID = new LinkedHashMap<>();
		for (int position = 0; position < snapshot.size(); position++) {
			Operator operator = snapshot.get(position);
			if (operator == null) throw new IllegalArgumentException(context + ": null operator at position " + position);
			if (seen.put(operator, Boolean.TRUE) != null) continue;
			String id = operator.getID();
			if (id == null || id.isBlank() || id.equals("unknown")) {
				throw new IllegalArgumentException(context + ": unusable ID " + id + " at position " + position + " class " + tuningClassLabel(operator));
			}
			if (byID.put(id, operator) != null) {
				throw new IllegalArgumentException(context + ": duplicate ID " + id + " on distinct objects at position " + position);
			}
		}
		return byID;
	}

	private static final class TuningPayload {
		private final int accepted, rejected, acceptedForCorrection, rejectedForCorrection;
		private final double scalar;

		private TuningPayload(Operator operator) {
			accepted = operator.get_m_nNrAccepted();
			rejected = operator.get_m_nNrRejected();
			acceptedForCorrection = operator.get_m_nNrAcceptedForCorrection();
			rejectedForCorrection = operator.get_m_nNrRejectedForCorrection();
			scalar = operator.getCoercableParameterValue();
		}

		private void applyTo(Operator operator) {
			operator.setAcceptedRejected(accepted, rejected, acceptedForCorrection, rejectedForCorrection);
			operator.setCoercableParameterValue(scalar);
		}
	}

	private static final class TuningPair {
		private final Operator first, second;
		private final TuningPayload firstPayload, secondPayload;
		private final String context;

		private TuningPair(Operator first, Operator second, TuningPayload firstPayload, TuningPayload secondPayload, String context) {
			this.first = first;
			this.second = second;
			this.firstPayload = firstPayload;
			this.secondPayload = secondPayload;
			this.context = context;
		}

		private void apply() {
			try {
				secondPayload.applyTo(first);
				firstPayload.applyTo(second);
			} catch (RuntimeException e) {
				// Arbitrary custom setter failures are fatal; no generic rollback is promised.
				throw new IllegalStateException(context + ": tuning commit failed; exchange cannot continue", e);
			}
		}
	}

	private static final class TuningTransferPlan {
		private final TuningPair[] pairs;

		private TuningTransferPlan(List<TuningPair> pairs) {
			this.pairs = pairs.toArray(new TuningPair[0]);
		}

		private void apply() {
			for (TuningPair pair : pairs) pair.apply();
		}
	}

	/* makes the schedule of when to swap which chains */
	@SuppressWarnings("unchecked")
	void buildSchedule(){
		// initialize Arrays
		runTillIteration = new ArrayList[chains.length];
		for (int i = 0; i < chains.length; i++){
			runTillIteration[i] = new ArrayList<Long>();
		}

		for (long sampleNr = resampleEvery; sampleNr <= chainLength; sampleNr += resampleEvery) {
			int i,j;
			// resample state
			i = Randomizer.nextInt(chains.length);
			
			j = i;
			while (i == j) {
				j = Randomizer.nextInt(chains.length);
			}
			if (!neighbourSwappingInput.get()) {
				runTillIteration[i].add(sampleNr);
				runTillIteration[j].add(sampleNr);
			}else {		
				for (int k = 0; k <runTillIteration.length; k++)
					runTillIteration[k].add(sampleNr);
			}
		}
	}
	
    private static final String CHECKPOINT_EPOCH = "POST_EXCHANGE_POST_ADAPTATION";
    private long committedSequence = 0;
    private String committedSlot;
    private long segmentControllerSample = 0;
    private Generation restoreGeneration;

    private static final class Generation {
        final String slot;
        final long sequence;
        final Map<String, String> fields;
        Path[] rankFiles;
        Generation(String slot, long sequence, Map<String, String> fields) {
            this.slot = slot;
            this.sequence = sequence;
            this.fields = fields;
        }
    }

    private Path descriptorPath(String slot) {
        return Path.of(stateFileName + ".mc3." + slot + ".commit").toAbsolutePath();
    }

    private static final long WORKER_POLL_MS = 50;
    private static final long FAILURE_DRAIN_MS = 5000;

    private void checkWorkerFailures() {
        // Physical order makes the primary deterministic among failures visible in this scan.
        for (int physical = 0; physical < threads.length; physical++) {
            if (threads[physical] instanceof HeatedChainThread worker && worker.failure != null) {
                throw new IllegalStateException("Heated chain failed: " + physical, worker.failure);
            }
        }
    }

    private void joinChain(int physical) {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Controller interrupted while waiting for workers");
                }
                checkWorkerFailures();
                Thread worker = threads[physical];
                if (worker == null || !worker.isAlive()) {
                    checkWorkerFailures();
                    return;
                }
                // Healthy workers have no runtime deadline; every poll checks all owned workers.
                worker.join(WORKER_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining chain " + physical, e);
        }
    }

    private void cancelAndDrainWorkers(Throwable primary) {
        // Clear only for cleanup joins and restore before the original failure escapes.
        boolean interrupted = Thread.interrupted();
        final long deadline = System.nanoTime() + FAILURE_DRAIN_MS * 1_000_000L;
        try {
            Thread[] workers = threads == null ? new Thread[0] : threads.clone();
            while (true) {
                boolean live = false;
                for (Thread worker : workers) {
                    if (worker != null && worker.isAlive()) {
                        live = true;
                        try {
                            worker.interrupt();
                        } catch (Throwable cleanupFailure) {
                            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
                        }
                    }
                }
                if (!live) return;
                for (Thread worker : workers) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    if (worker != null && worker.isAlive()) {
                        long waitNanos = Math.min(remaining, WORKER_POLL_MS * 1_000_000L);
                        try {
                            worker.join(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                if (System.nanoTime() >= deadline) break;
            }
            StringBuilder undrained = new StringBuilder();
            for (int physical = 0; physical < workers.length; physical++) {
                Thread worker = workers[physical];
                if (worker != null && worker.isAlive()) {
                    if (undrained.length() > 0) undrained.append("; ");
                    undrained.append("physical ").append(physical).append(" [")
                            .append(worker.getName()).append(", ").append(worker.getState()).append("]");
                }
            }
            if (undrained.length() > 0) {
                primary.addSuppressed(new IllegalStateException("Worker drain deadline exceeded after "
                        + FAILURE_DRAIN_MS + " ms: " + undrained));
            }
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void joinAllChains() {
        for (int k = 0; k < chains.length; k++) joinChain(k);
    }

    // Preserve the existing controller cadence: only exchange boundaries divisible by storeEvery.
    // Nonpositive storeEvery disables periodic controller commits; ordinary working files are separate.
    private boolean checkpointDue(long sample) {
        return storeEveryInput.get() > 0 && sample > 0 && sample % storeEveryInput.get() == 0;
    }

    private long nextCheckpointAfter(long sample) {
        long cadence = storeEveryInput.get();
        if (cadence <= 0) return Long.MAX_VALUE;
        long a = cadence, b = resampleEvery;
        while (b != 0) { long remainder = a % b; a = b; b = remainder; }
        long interval = Math.multiplyExact(cadence / a, (long) resampleEvery);
        long intervals = sample / interval;
        if (intervals == Long.MAX_VALUE || interval > Long.MAX_VALUE / (intervals + 1)) return Long.MAX_VALUE;
        return (intervals + 1) * interval;
    }

    private long nextAdaptationAfter(long after) {
        if (!optimise) return Long.MAX_VALUE;
        long exchangesUntilAdaptation = Math.max(1L, (long) optimiseDelayInput.get() - totalSwaps + 1L);
        // Bound the distance before multiplying so the prospective sample cannot overflow.
        if (exchangesUntilAdaptation > (chainLength - after) / resampleEvery) return Long.MAX_VALUE;
        return after + exchangesUntilAdaptation * resampleEvery;
    }

    private long nextPrescheduledStop(int physical, long after) {
        long participation = runTillIteration[physical].isEmpty() ? chainLength : runTillIteration[physical].get(0);
        return Math.min(chainLength,
                Math.min(participation, Math.min(nextCheckpointAfter(after), nextAdaptationAfter(after))));
    }

    private void resumePrescheduled(int physical, long after) {
        if (after >= chainLength) return;
        threads[physical] = new HeatedChainThread(physical, nextPrescheduledStop(physical, after));
        threads[physical].start();
    }

    private int[] verifyRankLadder() {
        int[] physicalForRank = new int[chains.length];
        java.util.Arrays.fill(physicalForRank, -1);
        for (int physical = 0; physical < chains.length; physical++) {
            HeatedChain chain = chains[physical];
            int rank = chain.getChainNr();
            if (rank < 0 || rank >= chains.length || physicalForRank[rank] != -1)
                throw new IllegalStateException("Checkpoint rank set is not a permutation");
            physicalForRank[rank] = physical;
            double expected = 1.0 / (1.0 + getTemperature(rank));
            if (!betaAgrees(chain.getBeta(), expected) || (rank == 0 && chain.getBeta() != 1.0))
                throw new IllegalStateException("Checkpoint ladder mismatch at rank " + rank);
        }
        return physicalForRank;
    }

    private static boolean betaAgrees(double actual, double expected) {
        return Double.isFinite(actual) && actual >= 0 && actual <= 1
                && Math.abs(actual - expected) <= 1e-12 * Math.max(1.0, Math.abs(expected));
    }

    private void verifyStoppedAt(long sample) {
        for (int k = 0; k < chains.length; k++) {
            if (threads != null && threads[k] != null && threads[k].isAlive())
                throw new IllegalStateException("Chain running at required stop: " + k);
            if (chains[k].getCurrentSample() != Math.addExact(sample, 1))
                throw new IllegalStateException("Chain did not stop at sample " + sample + ": " + k
                        + " next local sample=" + chains[k].getCurrentSample());
        }
    }

    private Map<String, String> restartConfiguration() {
        Map<String, String> signature = new TreeMap<>();
        signature.put("config.chains", nrOfChainsInput.get().toString());
        signature.put("config.resampleEvery", resampleEveryInput.get().toString());
        signature.put("config.spacing", spacing.name());
        signature.put("config.optimise", optimiseInput.get().toString());
        signature.put("config.optimiseDelay", optimiseDelayInput.get().toString());
        signature.put("config.target", Double.toHexString(targetAcceptanceProbabilityInput.get()));
        signature.put("config.maxAdaptation", Double.toHexString(maxAdaptationInput.get()));
        signature.put("config.maxTemperature", maxTemperatureInput.get() == null ? "null"
                : Double.toHexString(maxTemperatureInput.get()));
        signature.put("config.preSchedule", preScheduleInput.get().toString());
        signature.put("config.neighbourSwapping", neighbourSwappingInput.get().toString());
        signature.put("config.heatLikelihoodOnly", heatLikelihoodOnlyInput.get().toString());
        return signature;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** Validate the actual final bytes, not a successful return from BEAST's writer. */
    private static void validateRankFile(Path file, long absoluteSample) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) == 0)
            throw new IOException("Missing/empty/indirect committed rank file: " + file);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = factory.newDocumentBuilder().parse(file.toFile());
            if (!"itsabeastystatewerein".equals(doc.getDocumentElement().getTagName())
                    || !"2.0".equals(doc.getDocumentElement().getAttribute("version"))
                    || Long.parseLong(doc.getDocumentElement().getAttribute("sample")) != absoluteSample)
                throw new IOException("Committed rank XML/sample mismatch: " + file);
            String close = "</itsabeastystatewerein>";
            int at = text.indexOf(close);
            if (at < 0) throw new IOException("Missing BEAST state XML: " + file);
            String appendix = text.substring(at + close.length()).trim();
            if (!appendix.startsWith("<!--") || !appendix.endsWith("-->"))
                throw new IOException("Missing operator appendix: " + file);
            JSONObject operators = new JSONObject(appendix.substring(4, appendix.length() - 3).trim());
            JSONArray list = operators.getJSONArray("operators");
            for (int k = 0; k < list.length(); k++) list.getJSONObject(k);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Invalid composite rank checkpoint: " + file, e);
        }
    }

    private static Map<String, String> readDescriptor(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Indirect checkpoint descriptor: " + file);
        Map<String, String> fields = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals < 1 || fields.putIfAbsent(line.substring(0, equals), line.substring(equals + 1)) != null)
                throw new IOException("Malformed/duplicate checkpoint descriptor entry: " + file);
        }
        return fields;
    }

    private static String require(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name);
        if (value == null) throw new IOException("Missing checkpoint field " + name);
        return value;
    }

    private static long number(Map<String, String> fields, String name) throws IOException {
        try {
            long value = Long.parseLong(require(fields, name));
            if (value < 0) throw new NumberFormatException("negative");
            return value;
        } catch (NumberFormatException e) { throw new IOException("Invalid checkpoint integer " + name, e); }
    }

    private static List<Boolean> history(String text) throws IOException {
        List<Boolean> values = new ArrayList<>();
        if (!text.isEmpty()) for (String token : text.split(",", -1)) {
            if (!token.equals("true") && !token.equals("false")) throw new IOException("Invalid acceptedSwaps history");
            values.add(Boolean.valueOf(token));
        }
        return values;
    }

    private Generation readEnvelope(String slot) throws IOException {
        Path path = descriptorPath(slot);
        if (!Files.exists(path)) return null;
        Map<String, String> fields = readDescriptor(path);
        if (!require(fields, "format").equals("CoupledMCMC-checkpoint-v1")
                || !require(fields, "slot").equals(slot)) throw new IOException("Invalid checkpoint format/slot: " + path);
        long sequence = number(fields, "sequence");
        if (sequence == 0) throw new IOException("Checkpoint sequence must be positive");
        return new Generation(slot, sequence, fields);
    }

    private Generation discoverGeneration() throws IOException {
        // An unreadable envelope cannot safely be ordered; fail rather than guess/fall back.
        Generation a = readEnvelope("A"), b = readEnvelope("B");
        if (a == null && b == null) throw new IOException("Legacy/unqualified multi-chain restart: no v1 committed descriptor");
        if (a != null && b != null && a.sequence == b.sequence)
            throw new IOException("Conflicting/equal committed generation sequences");
        Generation newest = a == null ? b : b == null || a.sequence > b.sequence ? a : b;
        validateGeneration(newest); // Any corrupt newest publication is a hard failure, never a fallback.
        return newest;
    }

    private void validateGeneration(Generation generation) throws IOException {
        Map<String, String> f = generation.fields;
        Set<String> expectedKeys = new HashSet<>(java.util.Arrays.asList("format", "slot", "sequence", "epoch",
                "chains", "segment_controller_sample", "prior_sample_offset", "absolute_checkpoint_sample",
                "totalSwaps", "successfullSwaps", "successfullSwaps0", "deltaTemperature", "acceptedSwaps", "spacing"));
        Map<String, String> signature = restartConfiguration();
        expectedKeys.addAll(signature.keySet());
        for (Map.Entry<String, String> entry : signature.entrySet())
            if (!require(f, entry.getKey()).equals(entry.getValue())) throw new IOException("Restart configuration mismatch: " + entry.getKey());
        if (!require(f, "epoch").equals(CHECKPOINT_EPOCH) || number(f, "chains") != chains.length
                || !require(f, "spacing").equals(spacing.name())) throw new IOException("Checkpoint epoch/chains/spacing mismatch");
        long absolute = number(f, "absolute_checkpoint_sample");
        try {
            if (Math.addExact(number(f, "prior_sample_offset"), number(f, "segment_controller_sample")) != absolute)
                throw new IOException("Checkpoint coordinate invariant failed");
        } catch (ArithmeticException e) { throw new IOException("Checkpoint coordinate overflow", e); }
        long total = number(f, "totalSwaps"), success = number(f, "successfullSwaps"), cold = number(f, "successfullSwaps0");
        if (total > Integer.MAX_VALUE || cold > success || success > total)
            throw new IOException("Invalid checkpoint counters/history");
        List<Boolean> savedHistory = history(require(f, "acceptedSwaps"));
        long expectedHistoryLength = optimiseInput.get()
                ? Math.min(total, Math.max(0L, (long) optimiseDelayInput.get())) : 0L;
        if (savedHistory.size() != expectedHistoryLength)
            throw new IOException("Invalid checkpoint acceptedSwaps history length: expected "
                    + expectedHistoryLength + " but found " + savedHistory.size());
        final double savedDelta;
        try { savedDelta = Double.parseDouble(require(f, "deltaTemperature")); }
        catch (NumberFormatException e) { throw new IOException("Invalid saved deltaTemperature", e); }
        if (!Double.isFinite(savedDelta) || savedDelta < 0 || savedDelta != applySpacingBounds(savedDelta))
            throw new IOException("Saved deltaTemperature outside qualified bounds");
        generation.rankFiles = new Path[chains.length];
        Set<String> fileNames = new HashSet<>();
        for (int rank = 0; rank < chains.length; rank++) {
            String prefix = "rank." + rank + ".";
            for (String suffix : new String[]{"rank","file","sha256","beta","physical_at_commit"}) expectedKeys.add(prefix + suffix);
            if (number(f, prefix + "rank") != rank) throw new IOException("Duplicate/missing checkpoint rank");
            if (number(f, prefix + "physical_at_commit") >= chains.length) throw new IOException("Invalid diagnostic physical index");
            String name = require(f, prefix + "file");
            Path relative = Path.of(name);
            if (relative.isAbsolute() || relative.getNameCount() != 1 || name.equals(".") || name.equals("..")
                    || name.contains("\n") || name.contains("\r") || !fileNames.add(name))
                throw new IOException("Invalid/duplicate committed rank filename");
            Path path = descriptorPath(generation.slot).getParent().resolve(relative);
            validateRankFile(path, absolute);
            if (!sha256(path).equals(require(f, prefix + "sha256"))) throw new IOException("Committed rank SHA-256 mismatch: " + rank);
            double temperature = spacing == Spacing.Geometric ? rank * savedDelta
                    : savedDelta == 0 ? 0 : Math.expm1(-savedDelta * Math.log1p(-rank / (double) chains.length));
            try {
                double beta = Double.parseDouble(require(f, prefix + "beta"));
                if (!betaAgrees(beta, 1.0 / (1.0 + temperature)) || (rank == 0 && beta != 1.0))
                    throw new IOException("Saved beta/delta disagreement at rank " + rank);
            } catch (NumberFormatException e) { throw new IOException("Invalid saved beta", e); }
            generation.rankFiles[rank] = path;
        }
        if (!f.keySet().equals(expectedKeys)) throw new IOException("Unexpected/missing checkpoint fields or ranks");
    }

    private void preflightRestart() throws IOException {
        if (!restoreFromFile) {
            if (Files.exists(descriptorPath("A")) || Files.exists(descriptorPath("B")))
                throw new IOException("Fresh run would overwrite existing committed generations; use a new state path or resume");
            return;
        }
        Generation generation = discoverGeneration();
        Map<String, String> f = generation.fields;
        deltaTemperature = Double.parseDouble(require(f, "deltaTemperature"));
        totalSwaps = (int) number(f, "totalSwaps");
        successfullSwaps = (int) number(f, "successfullSwaps");
        successfullSwaps0 = (int) number(f, "successfullSwaps0");
        acceptedSwaps = history(require(f, "acceptedSwaps"));
        sampleOffset = number(f, "absolute_checkpoint_sample");
        segmentControllerSample = 0;
        committedSlot = generation.slot;
        committedSequence = generation.sequence;
        restoreGeneration = generation;
    }

    /** Establish every append target's coordinate before initRun can open the first logger. */
    private void preflightLogOutputs() throws IOException {
        if (restoreFromFile && Logger.FILE_MODE == Logger.LogFileMode.resume) {
            int activeChains = logHeatedChainsInput.get() ? chains.length : 1;
            for (int physical = 0; physical < activeChains; physical++) {
                for (Logger logger : loggersInput.get()) {
                    long cadence = logger.everyInput.get() == null ? 1 : logger.everyInput.get();
                    String name;
                    try {
                        name = CoupledLogger.restartFileName(logger, physical);
                    } catch (IOException e) {
                        throw incompatibleLog(String.valueOf(logger.fileNameInput.get()), "unparseable", cadence, e.getMessage());
                    }
                    if (name == null) continue;
                    Path path = Path.of(name);
                    if (cadence <= 0)
                        throw incompatibleLog(name, "unparseable", cadence, "cadence must be positive");
                    if (Files.notExists(path)) continue; // A new continuation output is permitted.
                    if (!Files.exists(path))
                        throw incompatibleLog(name, "unparseable", cadence, "cannot determine whether output exists");
                    final long tail;
                    try {
                        tail = readLogTail(path, CoupledLogger.restartLogMode(logger));
                    } catch (IOException | IllegalArgumentException e) {
                        throw incompatibleLog(name, "unparseable", cadence, e.getMessage());
                    }
                    if (sampleOffset % cadence != 0 || tail != sampleOffset)
                        throw incompatibleLog(name, Long.toString(tail), cadence,
                                "append requires C % cadence == 0 and L == C; use compatible logs or new output paths");
                }
            }
        }
        // Missing outputs do not initialize this inherited static field themselves.
        CoupledLogger.setManagedSampleOffset(restoreFromFile ? sampleOffset : -1);
    }

    private IOException incompatibleLog(String file, String tail, long cadence, String reason) {
        return new IOException("N2 incompatible log " + file + ": C=" + sampleOffset
                + " L=" + tail + " cadence=" + cadence + "; " + reason);
    }

    /** Reads physical tail lines only; never invokes Logger's destructive offset/repair helpers. */
    private static long readLogTail(Path path, Logger.LOGMODE mode) throws IOException {
        String last = null, previous = null;
        int columns = -1;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                previous = last;
                last = line;
                if (line.startsWith("Sample\t")) columns = line.split("\t").length;
            }
        }
        if (last == null) throw new IOException("empty output cannot prove a checkpoint tail");
        boolean ended;
        try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            if (file.length() == 0) throw new IOException("empty output");
            file.seek(file.length() - 1);
            int end = file.read();
            ended = end == '\n' || end == '\r';
        }
        String sample;
        if (mode == Logger.LOGMODE.tree) {
            // Tree.close prints End; without a newline; Logger's append opening removes it later.
            if (last.equals("End;")) last = previous;
            else if (!ended) throw new IOException("unterminated tree record");
            if (last == null) throw new IOException("missing tree record");
            var match = java.util.regex.Pattern.compile("^tree\\s+STATE_([0-9]+)\\s*=\\s*(.+);$").matcher(last);
            if (!match.matches() || !completeTreePayload(match.group(2)))
                throw new IOException("malformed or partial final tree record");
            sample = match.group(1);
        } else {
            if (!ended) throw new IOException("unterminated trace record");
            String[] fields = last.split("\t");
            if (columns < 1 || fields.length != columns || !fields[0].matches("[0-9]+"))
                throw new IOException("malformed or partial final trace row");
            sample = fields[0];
        }
        try { return Long.parseLong(sample); }
        catch (NumberFormatException e) { throw new IOException("unparseable final sample", e); }
    }

    private static boolean completeTreePayload(String payload) {
        if (payload.isBlank()) return false;
        int parentheses = 0, comments = 0;
        char quote = 0;
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '[') comments++;
            else if (c == ']') { if (--comments < 0) return false; }
            else if (comments == 0) {
                if (c == '(') parentheses++;
                else if (c == ')' && --parentheses < 0) return false;
            }
        }
        return parentheses == 0 && comments == 0 && quote == 0;
    }

    /** Test-only override seam for deterministic failure injection; inert in the production class.
     * No XML input, property or environment switch enables failures. */
    void checkpointFailurePoint(String stage) throws IOException { }

    void storeStateToFile(long currentSample) throws IOException {
        verifyStoppedAt(currentSample);
        int[] byRank = verifyRankLadder();
        long absolute = Math.addExact(sampleOffset, currentSample);
        String slot = committedSlot == null || committedSlot.equals("B") ? "A" : "B";
        long sequence = Math.addExact(committedSequence, 1);
        Path descriptor = descriptorPath(slot);
        Path stagedDescriptor = Path.of(descriptor + ".tmp");
        Files.deleteIfExists(descriptor); // Invalidate only the inactive generation before any rank writes.
        Files.deleteIfExists(stagedDescriptor);
        checkpointFailurePoint("AFTER_INVALIDATION");
        Map<String, String> fields = new TreeMap<>(restartConfiguration());
        fields.put("format", "CoupledMCMC-checkpoint-v1");
        fields.put("slot", slot);
        fields.put("sequence", Long.toString(sequence));
        fields.put("epoch", CHECKPOINT_EPOCH);
        fields.put("chains", Integer.toString(chains.length));
        fields.put("segment_controller_sample", Long.toString(currentSample));
        fields.put("prior_sample_offset", Long.toString(sampleOffset));
        fields.put("absolute_checkpoint_sample", Long.toString(absolute));
        fields.put("totalSwaps", Integer.toString(totalSwaps));
        fields.put("successfullSwaps", Integer.toString(successfullSwaps));
        fields.put("successfullSwaps0", Integer.toString(successfullSwaps0));
        fields.put("deltaTemperature", Double.toString(deltaTemperature));
        fields.put("acceptedSwaps", acceptedSwaps.toString().replace("[", "").replace("]", "").replace(" ", ""));
        fields.put("spacing", spacing.name());
        for (int rank = 0; rank < chains.length; rank++) {
            String prefix = "rank." + rank + ".";
            String name = descriptor.getFileName() + ".rank-" + rank + ".state";
            Path rankFile = descriptor.getParent().resolve(name);
            chains[byRank[rank]].storeCommittedState(rankFile, absolute);
            fields.put(prefix + "rank", Integer.toString(rank));
            fields.put(prefix + "file", name);
            fields.put(prefix + "beta", Double.toString(chains[byRank[rank]].getBeta()));
            fields.put(prefix + "physical_at_commit", Integer.toString(byRank[rank]));
            checkpointFailurePoint("AFTER_RANK_" + (rank + 1));
        }
        checkpointFailurePoint("AFTER_ALL_RANKS");
        for (int rank = 0; rank < chains.length; rank++) {
            String prefix = "rank." + rank + ".";
            Path file = descriptor.getParent().resolve(fields.get(prefix + "file"));
            validateRankFile(file, absolute);
            fields.put(prefix + "sha256", sha256(file));
        }
        Generation candidate = new Generation(slot, sequence, fields);
        validateGeneration(candidate);
        verifyStoppedAt(currentSample);
        checkpointFailurePoint("AFTER_VALIDATION");
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        Files.writeString(stagedDescriptor, text, StandardCharsets.UTF_8);
        if (!readDescriptor(stagedDescriptor).equals(fields)) throw new IOException("Staged descriptor verification failed");
        checkpointFailurePoint("BEFORE_PUBLICATION");
        // No fallback if atomic publication is unsupported. This is a process-failure contract, not a power-loss guarantee.
        Files.move(stagedDescriptor, descriptor, StandardCopyOption.ATOMIC_MOVE);
        committedSlot = slot;
        committedSequence = sequence;
        segmentControllerSample = currentSample;
        System.out.println("MC3_COMMIT slot=" + slot + " sequence=" + sequence + " local=" + currentSample + " absolute=" + absolute);
    }

	double getDelta() {
		double target = targetAcceptanceProbabilityInput.get();	

		double p = (double) successfullSwaps / totalSwaps;
		double p_local = 0.0;
		
		// compute the local acceptance probability
		acceptedSwaps.remove(0);
		// compute local acceptance probability
		for (int k = acceptedSwaps.size()/10; k < acceptedSwaps.size(); k++)
			if (acceptedSwaps.get(k))
				p_local += 1;
		
		p_local /= acceptedSwaps.size() - acceptedSwaps.size()/10;
		
		
		
		// check that the local and global acceptance probability are at the same relative position
		// compared to the target acceptance probability
		if ((p<target) && (p_local>target))
			return 0.0;
		else if ((p>target) && (p_local<target))
			return 0.0;		
		

		double swapsTransformed;
		
//		if (optimise==Optimise.Log) {
//			swapsTransformed = Math.log(totalSwaps + 1.0);
//		}else if (optimise==Optimise.Sqrt) {
//			swapsTransformed = Math.sqrt(totalSwaps);			
//		}else {
			swapsTransformed = (double) totalSwaps;		
//		}
				
		// update maxTemperature		
		double delta = (p - target) / swapsTransformed;
		
		// prevent too large adaptions that lead to overshooting
		
		if (delta>maxAdaptationInput.get())
			delta = maxAdaptationInput.get();
		if (delta<-maxAdaptationInput.get())
			delta = -maxAdaptationInput.get();
		
		
		return delta;
	}
	
}



