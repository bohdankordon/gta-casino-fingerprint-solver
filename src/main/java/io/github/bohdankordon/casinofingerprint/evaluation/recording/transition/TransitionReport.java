package io.github.bohdankordon.casinofingerprint.evaluation.recording.transition;

import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Guard;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.LifecycleGuardSimulation.Row;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Entry;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.Exit;
import io.github.bohdankordon.casinofingerprint.evaluation.recording.transition.TransitionSummary.InterRound;
import io.github.bohdankordon.casinofingerprint.runtime.LiveRecognitionState;
import io.github.bohdankordon.casinofingerprint.runtime.RecognitionConsensusTracker;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Renders the human-readable Stage 6C.1A transition report.
 *
 * <p>Evaluation only. The report separates the three things the stage must keep apart: measured
 * facts from the recordings, the design inference those facts support, and the cases the dataset
 * cannot prove. It states no production behaviour and recommends no timer.
 */
final class TransitionReport {

    private TransitionReport() {
    }

    static String render(RoundTransitionAnalysis.Options options,
            List<RoundTransitionAnalysis.SourceRun> sourceRuns,
            List<TransitionSummary.Row> summaries,
            List<LifecycleGuardSimulation.Row> guardRows,
            List<TransitionRun> runs,
            List<String> unexplainedAnswers,
            List<Path> contactSheets,
            long wallMillis) {
        StringBuilder text = new StringBuilder();
        text.append("Stage 6C.1A full-rate round-transition characterization\n");
        text.append("===================================================\n\n");
        text.append("Measured, not implemented. This report characterizes the round and hack\n");
        text.append("transitions of the two private Stage 6 recordings at full source frame rate.\n");
        text.append("It contains no state machine, no automation, no input, no sleep and no tuning.\n");
        text.append("The recognition system measured here is the frozen production one.\n\n");

        appendMethod(text, options);
        appendSources(text, sourceRuns);
        appendInventory(text, summaries, runs);
        appendInterRound(text, summaries);
        appendEntry(text, summaries);
        appendExit(text, summaries);
        appendResetExperiment(text, summaries);
        appendGuardSimulation(text, guardRows);
        appendUnexplained(text, unexplainedAnswers, summaries);
        appendSameAnswerLimitation(text);
        appendDecisionGate(text, summaries, guardRows);
        appendRunAppendix(text, runs);
        appendArtifacts(text, options, contactSheets, wallMillis);
        return text.toString();
    }

    private static void appendMethod(StringBuilder text, RoundTransitionAnalysis.Options options) {
        text.append("Method\n");
        text.append("------\n");
        text.append("  decode      sequential OpenCV decode of the whole source, no seek, no random access\n");
        text.append(String.format(Locale.ROOT,
                "  population  every decoded frame inside each hack window padded by %.1f s on both "
                        + "sides%n", options.paddingSeconds()));
        text.append("  recognition frozen production pipeline: layout-driven extraction, structural\n");
        text.append("              normalization, target and fragment matching, constrained assignment,\n");
        text.append("              conservative policy (RecognitionPolicy.defaultPolicy())\n");
        text.append("  geometry    2560x1440: bundled production layout, unmodified\n");
        text.append("              1920x1080: evaluation-only Stage 6B uniform 0.75 geometry\n");
        text.append(String.format(Locale.ROOT,
                "  consensus   unmodified RecognitionConsensusTracker, %d consecutive identical "
                        + "frames required%n",
                RecognitionConsensusTracker.DEFAULT_REQUIRED_CONSECUTIVE_FRAMES));
        text.append("  anchors     the committed hack windows and round intervals are APPROXIMATE by a\n");
        text.append("              few tenths of a second; they are used as anchors only and are never\n");
        text.append("              treated as frame-exact ground truth\n");
        text.append("  identity    FingerprintId plus the sorted selected candidate set; a fingerprint\n");
        text.append("              alone is never the lifecycle contract\n\n");
    }

    private static void appendSources(StringBuilder text,
            List<RoundTransitionAnalysis.SourceRun> sourceRuns) {
        text.append("Source integrity and population\n");
        text.append("-------------------------------\n");
        for (RoundTransitionAnalysis.SourceRun run : sourceRuns) {
            text.append(String.format(Locale.ROOT, "  %s %s%n", run.sourceId(), run.resolution()));
            text.append(String.format(Locale.ROOT,
                    "    file %d bytes, sha256 verified %s%n", run.sizeBytes(), run.sha256()));
            text.append(String.format(Locale.ROOT,
                    "    decoder %.6f fps, %.3f s advertised, %d frames decoded before the last "
                            + "analyzed window%n",
                    run.fps(), run.durationSeconds(), run.decodedFrames()));
            text.append(String.format(Locale.ROOT, "    geometry %s (%s)%n", run.geometry(),
                    run.layoutDescription()));
            text.append(String.format(Locale.ROOT,
                    "    analyzed %d full-rate transition frames in %d hack(s), %d ms wall%n",
                    run.analyzedFrames(), run.hacks(), run.wallMillis()));
        }
        long analyzed = sourceRuns.stream().mapToLong(RoundTransitionAnalysis.SourceRun::analyzedFrames)
                .sum();
        text.append(String.format(Locale.ROOT,
                "  total analyzed frames %d across %d source(s)%n%n", analyzed, sourceRuns.size()));
    }

    private static void appendInventory(StringBuilder text, List<TransitionSummary.Row> summaries,
            List<TransitionRun> runs) {
        long entries = count(summaries, TransitionKind.HACK_ENTRY);
        long interRound = count(summaries, TransitionKind.INTER_ROUND);
        long exits = count(summaries, TransitionKind.HACK_EXIT);
        text.append("Transition inventory and run compression\n");
        text.append("--------------------------------------\n");
        text.append(String.format(Locale.ROOT,
                "  transitions: %d total (%d hack entry, %d inter-round, %d hack exit)%n",
                summaries.size(), entries, interRound, exits));
        text.append(String.format(Locale.ROOT,
                "  compressed runs: %d across every analyzed region%n", runs.size()));
        long stableRuns = runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.STABLE_RECOGNIZED).count();
        long uncertainRuns = runs.stream()
                .filter(run -> run.state() == LiveRecognitionState.UNCERTAIN).count();
        text.append(String.format(Locale.ROOT,
                "  runs by state: %d stable, %d uncertain, %d candidate%n%n",
                stableRuns, uncertainRuns, runs.size() - stableRuns - uncertainRuns));
    }

    private static void appendInterRound(StringBuilder text, List<TransitionSummary.Row> summaries) {
        text.append("ROUND_1 -> ROUND_2 transitions (the carryover problem)\n");
        text.append("-------------------------------------------------\n");
        for (TransitionSummary.Row row : summaries) {
            if (!(row instanceof InterRound interRound)) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "%n  %s  %s -> %s%n", interRound.transitionId(),
                    interRound.oldAnswer().code(), interRound.newAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    nominal boundary %.3f s (annotation anchor, not a visual transition)%n",
                    interRound.nominalBoundaryMs() / 1000.0));
            text.append(String.format(Locale.ROOT,
                    "    1  last frame carrying the old recognized answer:  %s%n",
                    frameTime(interRound.lastOldRecognizedFrame(), interRound.lastOldRecognizedMs())));
            text.append(String.format(Locale.ROOT,
                    "    2  last frame with old STABLE_RECOGNIZED:          %s%n",
                    frameTime(interRound.lastOldStableFrame(), interRound.lastOldStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    3  first frame carrying the new recognized answer: %s%n",
                    frameTime(interRound.firstNewRecognizedFrame(),
                            interRound.firstNewRecognizedMs())));
            text.append(String.format(Locale.ROOT,
                    "    4  first frame with new STABLE_RECOGNIZED:         %s%n",
                    frameTime(interRound.firstNewStableFrame(), interRound.firstNewStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    5  uncertain decision between the answers:         %s%n",
                    interRound.uncertainFramesBetween() > 0 ? "YES" : "NO"));
            text.append(String.format(Locale.ROOT,
                    "    6  uncertain frames / gap duration:                %d frame(s) / %s%n",
                    interRound.uncertainFramesBetween(),
                    interRound.uncertainDurationMs() == null
                            ? "n/a"
                            : String.format(Locale.ROOT, "%d ms", interRound.uncertainDurationMs())));
            text.append(String.format(Locale.ROOT,
                    "    7  direct old -> new switch:                       %s%n",
                    interRound.directSwitch() ? "YES (no uncertain or third-answer frame between)"
                            : "NO"));
            text.append(String.format(Locale.ROOT,
                    "    8  third / unexplained recognized answers:         %d frame(s)%n",
                    interRound.unexplainedRecognizedFrames()));
            text.append(String.format(Locale.ROOT,
                    "    9  third answer that became stable:                %d episode(s)%n",
                    interRound.unexplainedStableEpisodes()));
            text.append(String.format(Locale.ROOT,
                    "   10  last old stable -> first new stable:            %s%n",
                    interRound.timeLastOldStableToFirstNewStableMs() == null
                            ? "n/a"
                            : String.format(Locale.ROOT, "%d ms",
                                    interRound.timeLastOldStableToFirstNewStableMs())));
            text.append(String.format(Locale.ROOT,
                    "   11  old answer at/after the nominal boundary:       %d frame(s), up to %d ms "
                            + "past it%n",
                    interRound.oldFramesAfterNominalBoundary(),
                    interRound.oldTimeAfterNominalBoundaryMs()));
            text.append(String.format(Locale.ROOT,
                    "   12  reset-only: old answer re-stabilizes:          %s%n",
                    interRound.resetReplayOldRestabilized() == null
                            ? "n/a (no frame reached the boundary)"
                            : interRound.resetReplayOldRestabilized() ? "YES" : "NO"));
            text.append(String.format(Locale.ROOT, "    notes: %s%n", interRound.notes()));
        }
        text.append('\n');
    }

    private static void appendEntry(StringBuilder text, List<TransitionSummary.Row> summaries) {
        text.append("HACK_ENTRY -> ROUND_1 transitions\n");
        text.append("---------------------------------\n");
        for (TransitionSummary.Row row : summaries) {
            if (!(row instanceof Entry entry)) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "%n  %s  expected round answer %s%n",
                    entry.transitionId(), entry.roundAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    last uncertain decision before any recognition:   %s (%d uncertain frame(s) "
                            + "before the first recognition)%n",
                    frameTime(entry.lastUncertainBeforeFirstRecognizedFrame(),
                            entry.lastUncertainBeforeFirstRecognizedMs()),
                    entry.uncertainFramesBeforeFirstRecognized()));
            text.append(String.format(Locale.ROOT,
                    "    first recognized answer:                          %s %s%n",
                    frameTime(entry.firstRecognizedFrame(), entry.firstRecognizedMs()),
                    entry.firstRecognizedAnswer() == null
                            ? "" : entry.firstRecognizedAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    first candidate-consensus frame:                  %s %s%n",
                    frameTime(entry.firstCandidateConsensusFrame(),
                            entry.firstCandidateConsensusMs()),
                    entry.firstCandidateConsensusAnswer() == null
                            ? "" : entry.firstCandidateConsensusAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    first stable answer:                              %s %s%n",
                    frameTime(entry.firstStableFrame(), entry.firstStableMs()),
                    entry.firstStableAnswer() == null ? "" : entry.firstStableAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    hack-window entry -> first stable:                %s%n",
                    entry.entryToFirstStableMs() == null
                            ? "n/a"
                            : String.format(Locale.ROOT, "%d ms", entry.entryToFirstStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    transient recognized answers before it:           %d frame(s) %s%n",
                    entry.transientRecognizedFrames(), entry.transientAnswers().isEmpty()
                            ? "(none)" : entry.transientAnswers().stream()
                                    .map(AnswerIdentity::code)
                                    .collect(java.util.stream.Collectors.joining("|"))));
            text.append(String.format(Locale.ROOT,
                    "    transient answer that became stable:              %s%n",
                    entry.transientAnswerBecameStable() ? "YES" : "NO"));
            text.append(String.format(Locale.ROOT,
                    "    unexplained recognized frames / stable episodes:  %d / %d%n",
                    entry.unexplainedRecognizedFrames(), entry.unexplainedStableEpisodes()));
            text.append(String.format(Locale.ROOT, "    notes: %s%n", entry.notes()));
        }
        text.append('\n');
        text.append("  UNCERTAIN is a refusal by the conservative policy, never a claim that no puzzle\n");
        text.append("  is on screen. The frames before the first recognition are ordinary gameplay or\n");
        text.append("  a puzzle that the policy refused; the trace keeps every raw decision either way.\n\n");
    }

    private static void appendExit(StringBuilder text, List<TransitionSummary.Row> summaries) {
        text.append("ROUND_2 -> HACK_EXIT transitions\n");
        text.append("-------------------------------\n");
        for (TransitionSummary.Row row : summaries) {
            if (!(row instanceof Exit exit)) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "%n  %s  round answer %s%n", exit.transitionId(),
                    exit.roundAnswer().code()));
            text.append(String.format(Locale.ROOT,
                    "    last recognized round answer:                     %s%n",
                    frameTime(exit.lastOldRecognizedFrame(), exit.lastOldRecognizedMs())));
            text.append(String.format(Locale.ROOT,
                    "    last stable round answer:                         %s%n",
                    frameTime(exit.lastOldStableFrame(), exit.lastOldStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    first uncertain frame after it:                   %s%n",
                    frameTime(exit.firstUncertainAfterLastStableFrame(),
                            exit.firstUncertainAfterLastStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    round answer disappears (after its first stable episode): %s%n",
                    frameTime(exit.firstUncertainAfterFirstStableFrame(),
                            exit.firstUncertainAfterFirstStableMs())));
            text.append(String.format(Locale.ROOT,
                    "    old answer reappears after disappearing:          %s%n",
                    exit.oldAnswerReappearsAfterDisappearing() ? "YES" : "NO"));
            text.append(String.format(Locale.ROOT,
                    "    old answer re-stabilizes after disappearing:      %s%n",
                    exit.oldAnswerRestabilizesAfterDisappearing() ? "YES" : "NO"));
            text.append(String.format(Locale.ROOT,
                    "    different answer after the last stable one:       %s (%d frame(s))%n",
                    exit.differentAnswerAppears() ? "YES" : "NO",
                    exit.differentRecognizedFramesAfterLastStable()));
            text.append(String.format(Locale.ROOT,
                    "    stable answer at/after the nominal hack end:      %s (%d episode(s))%n",
                    exit.stableAnswerAfterNominalHackEnd() ? "YES" : "NO",
                    exit.stableEpisodesAfterNominalHackEnd()));
            text.append(String.format(Locale.ROOT,
                    "    unexplained recognized frames / stable episodes:  %d / %d%n",
                    exit.unexplainedRecognizedFrames(), exit.unexplainedStableEpisodes()));
            text.append(String.format(Locale.ROOT, "    notes: %s%n", exit.notes()));
        }
        text.append('\n');
    }

    private static void appendResetExperiment(StringBuilder text,
            List<TransitionSummary.Row> summaries) {
        text.append("Consensus reset experiment (the naive idea)\n");
        text.append("-------------------------------------------\n");
        text.append("  Simulated offline: after the first round was consumed, reset the UNMODIFIED\n");
        text.append("  RecognitionConsensusTracker at the nominal boundary of the second round and keep\n");
        text.append("  feeding the real subsequent frames.\n\n");
        for (TransitionSummary.Row row : summaries) {
            if (!(row instanceof InterRound interRound)) {
                continue;
            }
            text.append(String.format(Locale.ROOT,
                    "  %-34s reset applied %s | old answer re-stabilized: %s | stable onsets after "
                            + "reset %s | first stable after reset %s%n",
                    interRound.transitionId(),
                    interRound.resetReplayStableOnsets() == null ? "NO" : "yes",
                    interRound.resetReplayOldRestabilized() == null
                            ? "n/a" : interRound.resetReplayOldRestabilized() ? "YES" : "no",
                    interRound.resetReplayStableOnsets() == null
                            ? "n/a" : interRound.resetReplayStableOnsets(),
                    interRound.resetReplayFirstStableMs() == null
                            ? "n/a"
                            : String.format(Locale.ROOT, "%.3f s %s",
                                    interRound.resetReplayFirstStableMs() / 1000.0,
                                    interRound.resetReplayFirstStableAnswer() == null
                                            ? "" : interRound.resetReplayFirstStableAnswer().code())));
        }
        long restabilized = summaries.stream()
                .filter(row -> row instanceof InterRound)
                .map(row -> (InterRound) row)
                .filter(interRound -> Boolean.TRUE.equals(interRound.resetReplayOldRestabilized()))
                .count();
        long total = count(summaries, TransitionKind.INTER_ROUND);
        text.append(String.format(Locale.ROOT,
                "%n  RESULT: a consensus reset alone re-stabilizes the OLD answer on %d of %d "
                        + "observed inter-round transitions.%n", restabilized, total));
        text.append("  Reset by itself is therefore NOT a round-boundary solution.\n\n");
    }

    private static void appendGuardSimulation(StringBuilder text, List<Row> guardRows) {
        text.append("Offline lifecycle guard simulation\n");
        text.append("----------------------------------\n");
        text.append("  Every guard is simulated offline over the real frames of each hack, each with its\n");
        text.append("  own instance of the production tracker. No guard is implemented in production.\n\n");
        for (Guard guard : LifecycleGuardSimulation.guards()) {
            text.append(String.format(Locale.ROOT, "  %s%n", guard.name()));
            List<Row> rows = guardRows.stream().filter(row -> row.guardId().equals(guard.name()))
                    .toList();
            if (guard == Guard.G4_CONSERVATIVE_HYBRID) {
                text.append("    not simulated by design: the stable-different-answer half is the G3\n");
                text.append("    rule below, and the independent transition witness does not exist yet.\n");
                text.append("    It stays the documented answer for the same-answer case.\n");
                continue;
            }
            for (Row row : rows) {
                text.append(String.format(Locale.ROOT,
                        "    %-34s consumed %s at %.3f s | first actionable after consumption: %s %s"
                                + " | first matches consumed %s | first matches new round %s%n",
                        row.transitionId(),
                        row.consumedAnswer() == null ? "n/a" : row.consumedAnswer().code(),
                        row.consumedAtMs() == null ? Double.NaN : row.consumedAtMs() / 1000.0,
                        row.firstActionableAfterConsumptionMs() == null
                                ? "none"
                                : String.format(Locale.ROOT, "%.3f s",
                                        row.firstActionableAfterConsumptionMs() / 1000.0),
                        row.firstActionableAfterConsumptionAnswer() == null
                                ? "" : row.firstActionableAfterConsumptionAnswer().code(),
                        text(row.firstActionableMatchesConsumed()),
                        text(row.firstActionableMatchesNewRound())));
                text.append(String.format(Locale.ROOT,
                        "    %-34s old answer actionable again %s | new round actionable %s | "
                                + "actionable events %d | suppressed same-identity onsets %d%s%n",
                        "",
                        text(row.oldAnswerReactivated()), text(row.nextRoundDiscovered()),
                        row.actionableEvents(), row.suppressedSameIdentityOnsets(),
                        row.latencyFromFirstStableNewMs() == null ? ""
                                : String.format(Locale.ROOT, " | latency from first stable new %d ms",
                                        row.latencyFromFirstStableNewMs())));
            }
            text.append(String.format(Locale.ROOT, "    %s%n%n", summary(guard, rows)));
        }
    }

    private static String summary(Guard guard, List<Row> rows) {
        long discovered = rows.stream().filter(row -> Boolean.TRUE.equals(row.nextRoundDiscovered()))
                .count();
        long reactivated = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.oldAnswerReactivated())).count();
        long suppressed = rows.stream().mapToLong(Row::suppressedSameIdentityOnsets).sum();
        return switch (guard) {
            case G0_CONSENSUS_ONLY -> String.format(Locale.ROOT,
                    "summary: the new round is discovered in %d of %d hacks, but the previous "
                            + "round's answer is actionable again in %d of them",
                    discovered, rows.size(), reactivated);
            case G1_RESET_AFTER_CONSUMPTION -> String.format(Locale.ROOT,
                    "summary: the OLD answer becomes the first actionable result again in %d of %d "
                            + "hacks; the new round is still discovered later in %d of them",
                    reactivated, rows.size(), discovered);
            case G2_REQUIRE_UNCERTAIN_GAP -> String.format(Locale.ROOT,
                    "summary: the new round is discovered in %d of %d hacks; old-answer "
                            + "reactivation in %d",
                    discovered, rows.size(), reactivated);
            case G3_ANSWER_IDENTITY_CHANGE -> String.format(Locale.ROOT,
                    "summary: %d same-identity onset(s) suppressed, old-answer reactivation in %d "
                            + "of %d hacks, new round discovered in %d of %d",
                    suppressed, reactivated, rows.size(), discovered, rows.size());
            case G4_CONSERVATIVE_HYBRID -> "summary: documented, not simulated";
        };
    }

    private static void appendUnexplained(StringBuilder text, List<String> unexplainedAnswers,
            List<TransitionSummary.Row> summaries) {
        long stable = summaries.stream().mapToLong(row -> {
            if (row instanceof InterRound interRound) {
                return interRound.unexplainedStableEpisodes();
            }
            if (row instanceof Entry entry) {
                return entry.unexplainedStableEpisodes();
            }
            return ((Exit) row).unexplainedStableEpisodes();
        }).sum();
        text.append("Unexplained answers during transitions\n");
        text.append("-------------------------------------\n");
        text.append(String.format(Locale.ROOT,
                "  recognized frames whose identity is neither round answer of their hack: %d%n",
                unexplainedAnswers.size()));
        text.append(String.format(Locale.ROOT,
                "  stable episodes of such an answer: %d%n", stable));
        for (String line : unexplainedAnswers) {
            text.append("    ").append(line).append('\n');
        }
        text.append("  (Strong false-positive semantics outside the padded hack region stay exactly the\n");
        text.append("  ones measured in Stage 6A/6B and are not re-measured here.)\n\n");
    }

    private static void appendSameAnswerLimitation(StringBuilder text) {
        text.append("Same-answer consecutive rounds (UNPROVEN CASE)\n");
        text.append("---------------------------------------------\n");
        text.append("  The recordings contain no two consecutive rounds with exactly the same answer\n");
        text.append("  identity (FingerprintId plus sorted correct candidate set). Nothing in this\n");
        text.append("  dataset can therefore show that two visually separate rounds with the SAME\n");
        text.append("  identity are distinct rounds.\n\n");
        text.append("  Consequences for a later lifecycle tracker:\n");
        text.append("    - an answer-identity-change guard safely suppresses the carryover observed\n");
        text.append("      here, because a suppressed answer is never promoted to a new round;\n");
        text.append("    - the same guard FAILS CLOSED if a new round reuses the consumed identity:\n");
        text.append("      the new round stays inactive instead of being guessed;\n");
        text.append("    - a repeated identical stable answer must NEVER be interpreted as a new\n");
        text.append("      round on its own;\n");
        text.append("    - distinguishing identical consecutive rounds safely needs an independent\n");
        text.append("      transition witness (a round/hack boundary signal that does not come from\n");
        text.append("      the answer identity itself). No such witness exists in this stage, and no\n");
        text.append("      probability is estimated anywhere as a substitute.\n\n");
    }

    private static void appendDecisionGate(StringBuilder text, List<TransitionSummary.Row> summaries,
            List<Row> guardRows) {
        List<InterRound> interRound = summaries.stream()
                .filter(row -> row instanceof InterRound)
                .map(row -> (InterRound) row)
                .toList();
        List<Entry> entries = summaries.stream()
                .filter(row -> row instanceof Entry)
                .map(row -> (Entry) row)
                .toList();
        List<Exit> exits = summaries.stream()
                .filter(row -> row instanceof Exit)
                .map(row -> (Exit) row)
                .toList();
        List<Row> g3 = guardRows.stream().filter(row -> row.guardId().equals("G3_ANSWER_IDENTITY_CHANGE"))
                .toList();

        long gapCount = interRound.stream().filter(row -> row.uncertainFramesBetween() > 0).count();
        long resetCount = interRound.stream()
                .filter(row -> Boolean.TRUE.equals(row.resetReplayOldRestabilized())).count();
        long g3Suppressed = g3.stream().mapToLong(Row::suppressedSameIdentityOnsets).sum();
        long g3Reactivated = g3.stream()
                .filter(row -> Boolean.TRUE.equals(row.oldAnswerReactivated())).count();
        long g3Discovered = g3.stream()
                .filter(row -> Boolean.TRUE.equals(row.nextRoundDiscovered())).count();
        long stableUnexplained = interRound.stream()
                .mapToLong(InterRound::unexplainedStableEpisodes).sum();
        long entryStableMatches = entries.stream()
                .filter(entry -> entry.firstStableAnswer() != null
                        && entry.firstStableAnswer().equals(entry.roundAnswer())).count();
        long exitStableAfterEnd = exits.stream()
                .filter(Exit::stableAnswerAfterNominalHackEnd).count();
        long exitRestabilized = exits.stream()
                .filter(Exit::oldAnswerRestabilizesAfterDisappearing).count();

        text.append("Decision gate for Stage 6C.1B\n");
        text.append("-----------------------------\n");
        text.append(String.format(Locale.ROOT,
                "  1  An UNCERTAIN gap between the old and the new answer exists on %d of %d "
                        + "observed%n     round-to-round transitions%s%n",
                gapCount, interRound.size(),
                gapCount == interRound.size() ? "" : " - NOT guaranteed by every observation"));
        text.append(String.format(Locale.ROOT,
                "  2  A consensus reset alone prevents carryover: NO. It re-stabilizes the OLD answer%n"
                        + "     on %d of %d transitions. Reset is not a boundary solution.%n",
                resetCount, interRound.size()));
        text.append(String.format(Locale.ROOT,
                "  3  Answer-identity change suppresses the observed carryover: it suppressed %d%n"
                        + "     same-identity stable onset(s), and the old answer became actionable "
                        + "again on %d of%n     %d hacks.%n",
                g3Suppressed, g3Reactivated, g3.size()));
        text.append(String.format(Locale.ROOT,
                "  4  Answer-identity change still detects the observed next rounds: %d of %d.%n",
                g3Discovered, g3.size()));
        text.append(String.format(Locale.ROOT,
                "  5  Unexplained stable answers during transitions: %d.%n", stableUnexplained));
        text.append(String.format(Locale.ROOT,
                "  6  Hack entry: the first stable answer equals the annotated round answer on %d of "
                        + "%d hacks.%n     Hack exit: a stable answer at/after the nominal hack end "
                        + "occurs on %d of %d, and the%n     round answer re-stabilizes after it "
                        + "disappeared on %d of %d.%n",
                entryStableMatches, entries.size(), exitStableAfterEnd, exits.size(),
                exitRestabilized, exits.size()));
        text.append("  7  What cannot be solved safely with the current signals: consecutive rounds\n");
        text.append("     with the exact same answer identity. An identity comparison cannot tell a\n");
        text.append("     repeated round from a still-visible previous round; a timing constant would\n");
        text.append("     only hide the problem and is not a correctness mechanism.\n");
        text.append("  8  An independent transition witness IS needed for that case. Recommendation\n");
        text.append("     for Stage 6C.1B: implement the lifecycle tracker with fail-closed,\n");
        text.append("     event-based invariants (consume only on stable answers, never re-act on the\n");
        text.append("     consumed identity, require an uncertain/absent gap or an independent\n");
        text.append("     boundary witness for the same-identity case) and measure the witness on real\n");
        text.append("     recordings before any input automation.\n\n");
    }

    private static void appendArtifacts(StringBuilder text, RoundTransitionAnalysis.Options options,
            List<Path> contactSheets, long wallMillis) {
        text.append("Artifacts (all ignored, below the output directory)\n");
        text.append("------------------------------------------------\n");
        text.append("  " + RoundTransitionAnalysis.FRAMES_CSV_REL
                + "   full-rate per-frame trace\n");
        text.append("  " + RoundTransitionAnalysis.RUNS_CSV_REL
                + "    compressed decision/consensus runs\n");
        text.append("  " + RoundTransitionAnalysis.SUMMARY_CSV_REL
                + " one row per transition\n");
        text.append("  " + RoundTransitionAnalysis.GUARD_CSV_REL
                + "  offline guard simulation\n");
        text.append("  " + RoundTransitionAnalysis.CONTACT_SHEET_DIRECTORY_REL
                + "/   review sheets (" + contactSheets.size() + ")\n");
        text.append(String.format(Locale.ROOT, "  total analysis wall time %.1f s%n",
                wallMillis / 1000.0));
        text.append("  output directory ").append(options.outputDir()).append('\n');
    }

    /** Raw run sequence per transition region, for review of the compression itself. */
    private static void appendRunAppendix(StringBuilder text, List<TransitionRun> runs) {
        text.append("\nAppendix: decision / consensus runs per transition region\n");
        text.append("----------------------------------------------------\n");
        String currentRegion = "";
        for (TransitionRun run : runs) {
            String region = run.sourceId() + " H" + run.hackId();
            if (!region.equals(currentRegion)) {
                currentRegion = region;
                text.append(String.format(Locale.ROOT, "%n  %s%n", region));
            }
            text.append("    ").append(run.describe())
                    .append(String.format(Locale.ROOT, " (%d ms)", run.durationMs())).append('\n');
        }
        text.append('\n');
    }

    private static long count(List<TransitionSummary.Row> summaries, TransitionKind kind) {
        return summaries.stream().filter(row -> row.values().get("transition_type")
                .equals(kind.name())).count();
    }

    private static String frameTime(Long frameIndex, Long timestampMs) {
        if (frameIndex == null || timestampMs == null) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "frame %d at %.3f s", frameIndex, timestampMs / 1000.0);
    }

    private static String text(Boolean value) {
        return value == null ? "n/a" : value ? "yes" : "no";
    }

}
