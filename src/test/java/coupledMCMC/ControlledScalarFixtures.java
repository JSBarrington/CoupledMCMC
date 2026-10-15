package coupledMCMC;

import beast.base.core.Input;
import beast.base.inference.Distribution;
import beast.base.inference.Operator;
import beast.base.inference.State;
import beast.base.inference.parameter.RealParameter;
import java.util.List;
import java.util.Random;

/** Fixture source for the planned MCMC/XML/kernel integration tests. */
public final class ControlledScalarFixtures {
    private ControlledScalarFixtures() { }

    public static class QuadraticScore extends Distribution {
        public final Input<RealParameter> xInput = new Input<>("x", "scalar state", Input.Validate.REQUIRED);
        public final Input<Boolean> likelihoodInput = new Input<>("likelihoodPart", "use likelihood component", false);
        @Override public void initAndValidate() { }
        @Override public double calculateLogP() {
            double x = xInput.get().getValue();
            logP = likelihoodInput.get() ? -2.0 - (x - 2.0) * (x - 2.0) : -1.0 - x * x / 2.0;
            return logP;
        }
        @Override public List<String> getArguments() { return List.of(xInput.get().getID()); }
        @Override public List<String> getConditions() { return List.of(); }
        @Override public void sample(State state, Random random) {
            throw new UnsupportedOperationException("Deterministic score fixture does not generate prior samples");
        }
    }

    public static class FixedStep extends Operator {
        public final Input<RealParameter> xInput = new Input<>("x", "scalar state", Input.Validate.REQUIRED);
        public final Input<Double> stepInput = new Input<>("step", "deterministic displacement", 1.0);
        public final Input<Double> logHastingsInput = new Input<>("logHastings", "controlled Hastings term", 0.0);
        @Override public void initAndValidate() { }
        @Override public double proposal() {
            RealParameter x = xInput.get();
            x.setValue(x.getValue() + stepInput.get());
            return logHastingsInput.get();
        }
    }
}
