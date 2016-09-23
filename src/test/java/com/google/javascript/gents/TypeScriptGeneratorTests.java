package com.google.javascript.gents;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.javascript.clutz.DeclarationGeneratorTests;
import com.google.javascript.jscomp.SourceFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import junit.framework.TestResult;
import junit.framework.TestSuite;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Describable;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.AllTests;

@RunWith(AllTests.class)
public class TypeScriptGeneratorTests {

  static final String singleTestPath = "singleTests";

  static final String TEST_EXTERNS_MAP = TypeScriptGeneratorTests
      .getTestDirPath("test_externs_map.json").toString();

  // Map of test filename -> Options for tests which need a specific set of options
  static final Map<String, Options> testOptions = ImmutableMap.<String, Options>builder()
      .put("externs_map.js", new Options(TypeScriptGeneratorTests.TEST_EXTERNS_MAP))
      .build();

  public static TestSuite suite() throws IOException {
    TestSuite suite = new TestSuite(TypeScriptGeneratorTests.class.getName());

    List<File> testFiles = getTestInputFiles(DeclarationGeneratorTests.JS, singleTestPath);
    for (final File input : testFiles) {
      File goldenFile = DeclarationGeneratorTests.getGoldenFile(input, ".ts");
      Options options = TypeScriptGeneratorTests.testOptions.get(input.getName());
      suite.addTest(new GoldenFileTest(input.getName(), goldenFile, input, options));
    }
    return suite;
  }

  static List<File> getTestInputFiles(FilenameFilter filter, String... dir) {
    File[] testFiles = getTestDirPath(dir).toFile().listFiles(filter);
    return Arrays.asList(testFiles);
  }

  static Path getTestDirPath(String... testDir) {
    Path p = getPackagePath();
    for (String dir : testDir) {
      p = p.resolve(dir);
    }
    return p;
  }

  static Path getPackagePath() {
    Path testDir = FileSystems.getDefault().getPath("src", "test", "java");
    String packageName = TypeScriptGeneratorTests.class.getPackage().getName();
    return testDir.resolve(packageName.replace('.', File.separatorChar));
  }

  static String getFileText(final File input) throws IOException {
    String text = Files.asCharSource(input, Charsets.UTF_8).read();
    String cleanText = DeclarationGeneratorTests.GOLDEN_FILE_COMMENTS_REGEXP
        .matcher(text)
        .replaceAll("");
    return cleanText;
  }

  private static final class GoldenFileTest implements junit.framework.Test, Describable {

    private final String testName;
    private final File sourceFile;
    private final File goldenFile;
    private final Options testOptions;

    private GoldenFileTest(String testName, File goldenFile, File sourceFile, Options testOptions) {
      this.testName = testName;
      this.goldenFile = goldenFile;
      this.sourceFile = sourceFile;

      if (testOptions == null) {
        this.testOptions = new Options();
      } else {
        this.testOptions = testOptions;
      }
    }

    @Override
    public void run(TestResult result) {
      result.startTest(this);

      TypeScriptGenerator gents;
      try {
        gents = new TypeScriptGenerator(this.testOptions);

        String basename = gents.pathUtil.getFileNameWithoutExtension(sourceFile.getName());
        String sourceText = getFileText(sourceFile);
        String goldenText = getFileText(goldenFile);

        Map<String, String> transpiledSource = gents.generateTypeScript(
            Collections.singleton(sourceFile.getName()),
            Collections.singletonList(SourceFile.fromCode(sourceFile.getName(), sourceText)),
            Collections.<SourceFile>emptyList());

        assertThat(transpiledSource).hasSize(1);
        assertThat(transpiledSource).containsKey(basename);
        assertThat(transpiledSource.get(basename)).isEqualTo(goldenText);
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
      return testName;
    }

    @Override
    public Description getDescription() {
      return Description.createTestDescription(TypeScriptGeneratorTests.class, testName);
    }
  }

  private TypeScriptGenerator gents;

  @Before
  public void setUp() throws FileNotFoundException {
    gents = new TypeScriptGenerator(new Options());
  }

  private Map<String, String> runGents(SourceFile... sourceFiles) {
    Set<String> sourceNames = Sets.newHashSet();
    for (SourceFile src : sourceFiles) {
      sourceNames.add(src.getName());
    }

    return gents.generateTypeScript(
        sourceNames,
        Lists.newArrayList(sourceFiles),
        Collections.<SourceFile>emptyList());
  }

  @Test
  public void testMultiFile() throws Exception {
    Map<String, String> result = runGents(
        SourceFile.fromCode("foo", "/** @type {number} */ var x = 4;"),
        SourceFile.fromCode("bar", "/** @const {string} */ var y = \"hello\";")
    );
    assertThat(result).hasSize(2);
    assertThat(result).containsEntry("bar", "var y: string = \"hello\";\n");
    assertThat(result).containsEntry("foo", "var x: number = 4;\n");
  }

  @Test
  public void testFileNameTrimming() {
    String filepath = "/this/is/a/path/to/../foo.bar";
    String filename = gents.pathUtil.getFileNameWithoutExtension(filepath);
    assertThat(filename).isEqualTo("foo");
  }

  @Test
  public void testNoExterns() throws Exception {
    Map<String, String> result = runGents(
        SourceFile.fromCode("foo", "/** @type {number} */ var x = 4;"),
        SourceFile.fromCode("bar", "/** @externs */ /** @const {string} */ var y = \"hello\";")
    );
    assertThat(result).hasSize(1);
    assertThat(result).containsEntry("foo", "var x: number = 4;\n");
  }
}
