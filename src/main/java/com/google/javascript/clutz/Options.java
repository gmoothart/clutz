package com.google.javascript.clutz;

import com.google.common.collect.Sets;
import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DiagnosticGroups;
import com.google.javascript.jscomp.DependencyOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

public class Options {

  @Option(name = "-o", usage = "output to this file", metaVar = "OUTPUT")
  String output = "-";

  @Option(name = "--debug", usage = "run in debug mode (prints compiler warnings)")
  boolean debug = false;

  @Option(name = "--externs",
      usage = "list of files to read externs definitions (as separate args)",
      metaVar = "EXTERN...",
      handler = StringArrayOptionHandler.class)
  List<String> externs = new ArrayList<>();

  @Option(name = "--depgraphs",
      usage = "only generate output for files listed as a root in the given depgraphs",
      metaVar = "file.depgraph...",
      handler = StringArrayOptionHandler.class)
  List<String> depgraphFiles = new ArrayList<>();

  @Option(name = "--strict_deps",
      usage = "generates no modules for nonroots (but does generate types), so that nonroots "
          + "cannot be imported by TypeScript code.")
  boolean strictDeps = false;

  @Option(name = "--depgraphs_filter_sources",
      usage = "only include sources from the arguments list that appear in the given depgraphs")
  boolean filterSourcesWithDepgraphs = false;

  @Option(name = "--emitPlatformExterns",
      usage = "emits platform externs, instead of omitting them in favor of TS lib.d.ts")
  boolean emitPlatformExterns;

  @Option(name = "--closure_entry_points",
      usage = "only generate output for the given entry points to the program. Must be" +
              " goog.provide'd symbols.",
      metaVar = "ENTRYPOINT...",
      handler = StringArrayOptionHandler.class)
  List<String> entryPoints = new ArrayList<>();

  @Argument
  List<String> arguments = new ArrayList<>();

  Depgraph depgraph;

  public CompilerOptions getCompilerOptions() {
    final CompilerOptions options = new CompilerOptions();
    options.setClosurePass(true);

    DependencyOptions deps = new DependencyOptions();
    deps.setDependencySorting(true);
    options.setDependencyOptions(deps);

    if (!this.entryPoints.isEmpty()) {
      options.setManageClosureDependencies(this.entryPoints);
    }

    // All diagnostics are WARNINGs (or off) and thus ignored unless debug == true.
    // Only report issues (and fail for them) that are specifically causing problems for Clutz.
    // The idea is to not do a general sanity check of Closure code, just make sure Clutz works.
    // Report missing types as errors.
    options.setCheckGlobalNamesLevel(CheckLevel.ERROR);
    // Report duplicate definitions, e.g. for accidentally duplicated externs.
    options.setWarningLevel(DiagnosticGroups.DUPLICATE_VARS, CheckLevel.ERROR);

    // Late Provides are errors by default, but they do not prevent clutz from transpiling.
    options.setWarningLevel(DiagnosticGroups.LATE_PROVIDE, CheckLevel.OFF);

    options.setLanguage(CompilerOptions.LanguageMode.ECMASCRIPT6);
    options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT5);
    options.setCheckTypes(true);
    options.setInferTypes(true);
    options.setIdeMode(true); // So that we can query types after compilation.
    return options;
  }

  Options(String[] args) throws CmdLineException {
    CmdLineParser parser = new CmdLineParser(this);
    parser.parseArgument(args);
    depgraph = Depgraph.parseFrom(depgraphFiles);
    if (filterSourcesWithDepgraphs) {
      // Clutz still takes the list of files to compile from the outside, because Closure depends
      // on source order in many places. The depgraph files are not sorted, build order is instead
      // established by the outside tool driving compilation (e.g. bazel).
      Set<String> merged = Sets.union(depgraph.getRoots(), depgraph.getNonroots());
      arguments.retainAll(merged);
    }
    externs.addAll(depgraph.getExterns());
    if (!strictDeps) {
      depgraph = depgraph.withNonrootsAsRoots();
    }
    if (arguments.isEmpty() && externs.isEmpty()) {
      throw new CmdLineException(parser, "No files or externs were given");
    }
  }

  Options() {}
}
