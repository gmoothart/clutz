package com.google.javascript.gents;

import com.google.javascript.jscomp.CheckLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DiagnosticGroups;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

/**
 * A class that parses the command line arguments and generates {@code CompilerOptions} to use with
 * Closure Compiler.
 */
public class Options {

  @Option(name = "-o", usage = "output to this directory", metaVar = "OUTPUT")
  String output = "-";

  @Option(name = "--root", usage = "root directory of imports", metaVar = "ROOT")
  String root = null;

  @Option(name = "--debug", usage = "run in debug mode (prints compiler warnings)")
  boolean debug = false;

  @Option(name = "--convert",
      usage = "list of all files to be converted to TypeScript\n"
          + "This list of files does not have to be mutually exclusive from the source files",
      metaVar = "CONV...",
      handler = StringArrayOptionHandler.class)
  List<String> filesToConvert = new ArrayList<>();

  @Option(name = "--externs",
      usage = "list of files to read externs definitions (as separate args)",
      metaVar = "EXTERN...",
      handler = StringArrayOptionHandler.class)
  List<String> externs = new ArrayList<>();

  @Option(name = "--externsMap",
      usage = "File mapping externs to their TypeScript typings equivalent. Formatted as json",
      metaVar = "EXTERNSMAP")
  String externsMapFile = null;

  @Argument
  List<String> arguments = new ArrayList<>();

  Set<String> srcFiles = new LinkedHashSet<>();

  public CompilerOptions getCompilerOptions() {
    final CompilerOptions options = new CompilerOptions();
    options.setClosurePass(true);

    options.setCheckGlobalNamesLevel(CheckLevel.ERROR);
    // Report duplicate definitions, e.g. for accidentally duplicated externs.
    options.setWarningLevel(DiagnosticGroups.DUPLICATE_VARS, CheckLevel.ERROR);

    options.setLanguage(CompilerOptions.LanguageMode.ECMASCRIPT6);
    options.setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE);

    // Do not transpile module declarations
    options.setWrapGoogModulesForWhitespaceOnly(false);
    // Stop escaping the characters "=&<>"
    options.setTrustedStrings(true);

    // Compiler passes must be disabled to disable down-transpilation to ES5.
    options.skipAllCompilerPasses();
    setIdeMode(options);

    return options;
  }

  @SuppressWarnings("deprecation")
  private void setIdeMode(final CompilerOptions options) {
    // So that we can query types after compilation.
    options.setIdeMode(true);
  }

  Options(String[] args) throws CmdLineException {
    CmdLineParser parser = new CmdLineParser(this);
    parser.parseArgument(args);
    srcFiles.addAll(arguments);
    srcFiles.addAll(filesToConvert);

    if (srcFiles.isEmpty()) {
      throw new CmdLineException(parser, "No files were given");
    }
  }

  Options() {
    this.externsMapFile = externsMapFile;
  }

  Options(String externsMapFile) {
    this.externsMapFile = externsMapFile;
  }
}
