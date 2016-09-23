package com.google.javascript.gents;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.clutz.DeclarationGeneratorTests;
import com.google.javascript.jscomp.SourceFile;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import org.junit.runner.Describable;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.AllTests;

@RunWith(AllTests.class)
public class TypeScriptGeneratorMultiTests extends TypeScriptGeneratorTests {

  static final String multiTestPath = "multiTests";

  public static final FilenameFilter DIR = new FilenameFilter() {
    @Override
    public boolean accept(File dir, String name) {
      return new File(dir, name).isDirectory();
    }
  };

  public static TestSuite suite() throws IOException {
    TestSuite suite = new TestSuite(TypeScriptGeneratorMultiTests.class.getName());

    List<File> testFiles = getTestInputFiles(DIR, multiTestPath);
    for (final File input : testFiles) {
      suite.addTest(new GoldenDirTest(input.getName()));
    }
    return suite;
  }

  private static final class GoldenDirTest implements junit.framework.Test, Describable {

    private final String dirName;

    private GoldenDirTest(String testName) {
      this.dirName = testName;
    }

    @Override
    public void run(TestResult result) {
      result.startTest(this);

      try {
        TypeScriptGenerator gents = new TypeScriptGenerator(new Options());

        List<File> testFiles = getTestInputFiles(DeclarationGeneratorTests.JS,
            multiTestPath, dirName);

        Set<String> sourceNames = Sets.newHashSet();
        List<SourceFile> sourceFiles = Lists.newArrayList();
        Map<String, String> goldenFiles = Maps.newHashMap();

        for (final File sourceFile : testFiles) {
          String sourceText = getFileText(sourceFile);
          String filename = sourceFile.getName();
          sourceFiles.add(SourceFile.fromCode(sourceFile.getName(), sourceText));

          if (!filename.endsWith("_keep.js")) {
            sourceNames.add(sourceFile.getName());

            String basename = gents.pathUtil.getFileNameWithoutExtension(sourceFile.getName());
            File goldenFile = DeclarationGeneratorTests.getGoldenFile(sourceFile, ".ts");
            String goldenText = getFileText(goldenFile);
            goldenFiles.put(basename, goldenText);
          }
        }

        Map<String, String> transpiledSource = gents.generateTypeScript(
            sourceNames,
            sourceFiles,
            Collections.<SourceFile>emptyList());

        assertThat(transpiledSource).hasSize(sourceNames.size());
        for (String basename : goldenFiles.keySet()) {
          String goldenText = goldenFiles.get(basename);
          assertThat(transpiledSource).containsKey(basename);
          assertThat(transpiledSource.get(basename)).isEqualTo(goldenText);
        }
      } catch (Throwable t) {
        result.addError(this, t);
      } finally {
        result.endTest(this);
      }
    }

    @Override
    public int countTestCases() {
      return 1;
    }

    @Override
    public String toString() {
      return dirName;
    }

    @Override
    public Description getDescription() {
      return Description.createTestDescription(TypeScriptGeneratorMultiTests.class, dirName);
    }
  }
}
