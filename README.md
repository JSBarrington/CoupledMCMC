# CoupledMCMC for BEAST

CoupledMCMC implements Metropolis-coupled Markov chain Monte Carlo (MC³, or parallel tempering) for BEAST. It maintains multiple resident chains at different inverse temperatures and proposes exchanges of temperature/rank assignments. Rank 0 is the unheated rank (`beta = 1`).

**Project status.** The source described here is a **qualified, isolated engineering candidate**, not an announced BEAST package release or an approved production configuration. A fresh offline `clean verify` on 15 October 2026 passed 123 regression tests in 14 suites (zero failures, errors or skips), and the resulting archive matched the previously qualified package's uncompressed executable/source payloads. This establishes the stated test and packaging scope, not correctness for every BEAST model, operator or scientific analysis.

## Version and tested environment

The tested candidate used:

- **Java/Javac:** Azul Zulu 25.0.4.1
- **Build tool:** Maven 3.9.16, offline, isolated test/build directories
- **BEAST.base dependency:** 2.8.0-beta5
- **Platform:** macOS 26.6.2, Apple Silicon (aarch64)
- **Declared Java module:** `coupled.mcmc`

The source currently has **distinct version fields**: `pom.xml` declares Maven coordinates `io.github.compevol:coupled-mcmc:1.3.0-beta1`, whereas `version.xml` and the BEAST package ZIP naming use `1.3.0`. These are descriptive identities of the tested development artifacts, **not evidence that either version is published or installable**. They must be reconciled before a release is declared.

No tested package-manager installation command, BEAUti launcher workflow or general named-module runtime compatibility is claimed here. Check the intended host runtime and plugin versions independently before attempting an analysis.

## Model and XML integration

The coupled controller is `coupledMCMC.CoupledMCMC`. An analysis must provide its posterior distribution, state, operators and loggers as required by the controller and its BEAST dependencies. Replacing only the `spec` attribute of an arbitrary MCMC XML does **not** establish compatibility.

The source also includes `MCMC2CoupledMCMC`. Its `xml` and `output` inputs select an original MCMC XML and destination CoupledMCMC XML. It carries the model/state, operators, loggers and selected settings into the converted analysis; **it does not copy every MCMC/controller setting**. Inspect and validate the generated XML before using it. This describes source behavior, not a verified command-line launcher or GUI installation procedure.

### Controller inputs

The following defaults are declared in the tested source. They describe the multi-chain controller unless noted otherwise; with `chains=1`, execution delegates to ordinary MCMC (including its restart and logging behavior).

| Input | Default | Meaning |
| --- | --- | --- |
| `chains` | `2` | Number of resident chains; at least one is required. |
| `resampleEvery` | `100` | Positive local-sample interval between exchange opportunities. |
| `preSchedule` | `true` | Precompute exchange participation rather than selecting pairs at runtime. |
| `neighbourSwapping` | `false` | Select adjacent **current ranks**; forces runtime scheduling. |
| `optimise` | `true` | Adapt temperature spacing toward the configured acceptance target. |
| `optimiseDelay` | `100` | Begin adaptation after this number of attempted swaps. |
| `target` | `0.234` | Adaptation target, **not** a guaranteed or universally optimal acceptance rate. |
| `maxAdaptation` | `0.001` | Maximum adaptation step before applying spacing bounds. |
| `deltaTemperature` | `0.1` | Finite, nonnegative spacing parameter, with mode-specific meaning below. |
| `maxTemperature` | unset | Optional finite, nonnegative upper bound on the hottest internal temperature. |
| `useBetaDistribution` | `false` | Use Beta spacing instead of the source's geometric mode. |
| `logHeatedChains` | `false` | Enable heated-chain file logging as well as cold output. |
| `heatLikelihoodOnly` | `false` | Temper the likelihood alone instead of the full posterior. |

Exchange opportunities occur at multiples of `resampleEvery` through the configured chain length. Acceptance is not guaranteed. Prescheduled operation can allow nonparticipating workers to continue between necessary all-chain barriers; adaptation and committed checkpoints require all chains to stop at the correct coordinate. Runtime pair selection joins all chains at every opportunity. These modes are not interchangeable performance claims.

By default, a chain's score is `beta * logPosterior`. With `heatLikelihoodOnly=true`, it is `logPosterior - logLikelihood + beta * logLikelihood`; the model must include a distribution with ID `likelihood`. The likelihood-only option is a source-level contract, not a general scientific correctness qualification for arbitrary plugins.

### Temperature spacing

For `n > 1` chains, rank `r` from 0 through `n-1`, and bounded spacing `d`, `beta_r = 1 / (1 + T_r)`.

- **Geometric mode (source nomenclature):** `T_r = r*d`, hence `beta_r = 1/(1+r*d)`. Here `d` is an adjacent internal-temperature increment.
- **Beta spacing:** `T_r = (1-r/n)^(-d)-1`, hence `beta_r = (1-r/n)^d`. Here `d` is **not** an adjacent-temperature increment.
- **Zero spacing:** Every rank has `beta=1`, although rank identities remain distinct.

Nonfinite or negative configured spacings/caps are rejected; negative internal adaptation proposals are clamped to zero. With `maxTemperature=M`, the spacing bound is `M/(n-1)` for geometric mode and `log(1+M)/log(n)` for Beta spacing. The bound is applied at initialization and after adaptation, including with `optimise=false`.

## Exchange semantics and operator tuning

State, chain and active operator objects remain resident in their physical slots. On an accepted exchange, the controller exchanges inverse temperatures/current ranks, matching logger output routing, mutable working-state filenames and a **bounded** operator-tuning payload. Worker RNG names remain attached to physical slots. These mechanics do not imply identical trajectories under alternative scheduling or seeds.

Tuning transfer matches active resident operators by usable unique ID and exact Java class identity, not by list position. Missing/blank/`unknown` IDs, duplicate IDs on distinct objects, incompatible operator sets or classes, and cross-chain object aliases are rejected. Duplicate references to the **same** operator object are counted once.

Exactly five operator fields are transferred: accepted count, rejected count, accepted-for-correction count, rejected-for-correction count and the coercable scalar. Hidden adaptation state, covariance, selection weights, and nested/meta-operator histories are **not** covered. A custom setter failure is fatal; generic transactional rollback is not promised.

Logger streams are exchanged by matching logger IDs. When heated logging is disabled, suppression follows matching entries so output can follow the cold rank. Arbitrary logger layouts and external Logger subclasses have not been qualified.

## Workers and random number generators

Each controller execution prepares RNG lookup names associated with physical chain slots. Replacement worker threads in that execution reuse those names across segments and rank exchanges; new executions get new namespaces. The dependency can retain entries from completed executions.

The qualified worker contract assumes a **single controller** and no uncoordinated shared-Randomizer registration or global reseeding during preparation/sampling. This does not establish statistical independence, exact RNG replay, or arbitrary concurrent-controller and `MultiCoupledMCMC` safety.

Healthy workers have no new runtime deadline. A fatal controller/worker failure or interruption requests cooperative cancellation; workers inspect interruption between proposals. Cleanup uses a single shared **5,000 ms drain limit**, preserves the primary failure and records cleanup problems separately. An arbitrary noninterruptible custom operator may outlive that limit.

## Checkpoint and restart

### Mutable working state versus committed generations

Initial mutable working-state filenames are derived from the **basename** of the controller state path; only a terminal `.state` is replaced:

| Controller basename | Physical working files (slots 0, 1) |
| --- | --- |
| `run.state` | `run.0state`, `run.1state` |
| `run.checkpoint` | `run.checkpoint.0state`, `run.checkpoint.1state` |
| `run.state.backup.state` | `run.state.backup.0state`, `run.state.backup.1state` |

Working files can move during exchanges. They are distinct from committed rank-state files and alternating `.mc3.A.commit` / `.mc3.B.commit` controller descriptors.

A committed generation describes a **post-exchange, post-adaptation** boundary. All chains must be stopped at the same coordinate; the controller verifies rank permutation and expected inverse temperatures. State and operator schedules are recorded by **rank**. Each generation is written into the inactive A/B slot and its staged descriptor is published **last**, using an atomic move. A partially unpublished slot does not supersede the last committed generation. Power-loss durability is not established, and a reused slot is not an archival history.

Periodic controller commitments occur only when a positive local exchange coordinate is also divisible by positive `storeEvery`. `storeEvery <= 0` disables periodic controller commitments, separate from mutable working-file behavior. There is no promise of a committed generation at an ineligible final coordinate.

### Restore admission and log continuity

Restore chooses the highest published generation sequence and validates its descriptor/configuration, coordinates, committed rank-file structure and checksums. Unreadable descriptors, equal published sequences or a corrupt newest published generation **fail closed**; they do not authorize fallback to an older generation. Legacy uncommitted multi-chain state is not an authorized restart format. Fresh runs refuse pre-existing committed descriptors at the chosen state path.

At a valid restart from absolute checkpoint `C`, the resumed controller begins at local zero, so a subsequent local coordinate `s` represents absolute coordinate `C+s`. Selected rank-addressed states, operator schedules, counters, recent swap history and spacing are restored; future pairing schedules are regenerated. Exact previous thread, RNG or scheduling trajectories are not replayed.

For existing trace/tree files opened in **append mode**, every active file must end with a complete final logged sample `L` equal to `C`, and `C` must be divisible by that logger's cadence `E` (`L=C` and `C % E=0`). Ahead, behind, incomplete, malformed, empty or off-cadence existing tails are rejected before opening loggers or sampling. The controller does **not** automatically truncate, pad or reconcile them.

All-new resume output files are permitted and start a **new continuation**, not a fabricated seamless append; inherited Logger behavior applies cadence to local samples and adds the offset. Actual stdout is outside file-tail comparison. Enabled heated-chain files are included; suppressed files are not. Filename projection accounts for the configured prefix and tree token. These rules were tested with the frozen trace/tree Logger behavior, not every third-party logging implementation.

### Unchanged-model restriction

Multi-chain restart is qualified only for the **same model, data, state, operator and software configuration**. A selected controller-signature check and rank-file validation exist, but **no general StateNode/operator/model fingerprint** is implemented. Changing taxa, loci, operator classes or model dimensions across restart is not established as safe.

## Evidence and limits

The 15 October 2026 isolated offline qualification ran one `clean verify` of the prepared 42-file source candidate, with 123 tests across 14 suites passing. The resulting BEAST package archive's uncompressed runtime and source-JAR payloads matched the earlier qualified assembly; ZIP/container bytes were not required to be identical. The package includes the existing source/runtime resources but **does not embed this repository README**.

The tests establish bounded engineering regressions, source/build/packaging identity and selected synthetic runtime behavior. They **do not** prove an unbiased or correct posterior for arbitrary models, stationarity, superior ESS/mixing, exact restart or RNG trajectory replay, support for unrelated plugins or operating systems, official package publication, broad Java named-module deployment compatibility, or maintainer acceptance. No deployment or installation is asserted.

## Citation

Müller NF, Bouckaert RR. Adaptive Metropolis-coupled MCMC for BEAST 2. *PeerJ*. 2020;8:e9473. [DOI:10.7717/peerj.9473](https://doi.org/10.7717/peerj.9473).

Altekar G, Dwarkadas S, Huelsenbeck JP, Ronquist F. Parallel Metropolis coupled Markov chain Monte Carlo for Bayesian phylogenetic inference. *Bioinformatics*. 2004;20(3):407–415. [DOI:10.1093/bioinformatics/btg427](https://doi.org/10.1093/bioinformatics/btg427).
