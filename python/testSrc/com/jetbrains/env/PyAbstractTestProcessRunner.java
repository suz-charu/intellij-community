package com.jetbrains.env;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.Filter;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.jetbrains.python.run.AbstractPythonRunConfigurationParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;

import javax.swing.*;

/**
 * Runner for tests. Provides access to test console and test results.
 *
 * @author Ilya.Kazakevich
 */
public class PyAbstractTestProcessRunner<CONF_T extends AbstractPythonRunConfigurationParams>
  extends ConfigurationBasedProcessRunner<CONF_T> {

  /**
   * Filter to exclude suites
   */
  public static final Filter<SMTestProxy> NOT_SUIT = new Filter<SMTestProxy>() {
    @Override
    public boolean shouldAccept(SMTestProxy test) {
      return !test.isSuite();
    }
  };

  /**
   * Test results
   */
  protected SMTestProxy.SMRootTestProxy myTestProxy;
  private SMTRunnerConsoleView myExecutionConsole;

  /**
   * @see ConfigurationBasedProcessRunner#ConfigurationBasedProcessRunner(ConfigurationFactory, Class, String)
   */
  public PyAbstractTestProcessRunner(@NotNull final ConfigurationFactory configurationFactory,
                                     @NotNull final Class<CONF_T> expectedConfigurationType,
                                     @Nullable final String workingFolder) {
    super(configurationFactory, expectedConfigurationType, workingFolder);
  }


  @Override
  protected void fetchConsoleAndSetToField(@NotNull final RunContentDescriptor descriptor) {
    // Fetch test results from console
    myExecutionConsole = (SMTRunnerConsoleView)descriptor.getExecutionConsole();
    final JComponent component = myExecutionConsole.getComponent();
    assert component != null;
    myConsole = (ConsoleViewImpl)myExecutionConsole.getConsole();
    myTestProxy = myExecutionConsole.getResultsViewer().getTestsRootNode();
  }

  @Override
  protected void prepareConsoleAfterProcessEnd() {
    super.prepareConsoleAfterProcessEnd();
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        // Print output of tests to console (because console may be scrolled)
        myTestProxy.getAllTests().get(0).printOn(myExecutionConsole.getPrinter());
      }
    }, ModalityState.NON_MODAL);
  }

  /**
   * Ensures all test passed
   */
  public void assertAllTestsPassed() {
    final String consoleText = getAllConsoleText();
    Assert.assertEquals(consoleText, 0, myTestProxy.getChildren(Filter.NOT_PASSED).size());
    Assert.assertEquals(consoleText, 0, getFailedTestsCount());
  }

  /**
   * Searches for test by its name recursevly in {@link #myTestProxy}
   *
   * @param testName test name to find
   * @return test
   * @throws AssertionError if no test found
   */
  @NotNull
  public AbstractTestProxy findTestByName(@NotNull final String testName) {
    final AbstractTestProxy test = findTestByName(testName, myTestProxy);
    assert test != null : "No test found with name" + testName;
    return test;
  }

  /**
   * @return test results proxy
   */
  @NotNull
  public SMRootTestProxy getTestProxy() {
    return myTestProxy;
  }


  /**
   * Searches for test by its name recursevly in test, passed as arumuent.
   *
   * @param testName test name to find
   * @param test     root test
   * @return test or null if not found
   */
  @Nullable
  private static AbstractTestProxy findTestByName(@NotNull final String testName, @NotNull final AbstractTestProxy test) {
    if (test.getName().equals(testName)) {
      return test;
    }
    for (final AbstractTestProxy testProxy : test.getChildren()) {
      final AbstractTestProxy result = findTestByName(testName, testProxy);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /**
   * @return number of failed tests
   */
  public int getFailedTestsCount() {
    return myTestProxy.collectChildren(NOT_SUIT.and(Filter.FAILED_OR_INTERRUPTED)).size();
  }

  /**
   * @return number of passed tests
   */
  public int getPassedTestsCount() {
    return myTestProxy.collectChildren(NOT_SUIT.and(Filter.PASSED)).size();
  }

  /**
   * Ensures tests finished
   */
  public void assertFinished() {
    Assert.assertTrue("State is " + myTestProxy.getMagnitudeInfo().getTitle() + "\n" + getAllConsoleText(),
                      myTestProxy.wasLaunched() && !myTestProxy.wasTerminated());
  }

  /**
   * @return number of all tests
   */
  public int getAllTestsCount() {
    return myTestProxy.collectChildren(NOT_SUIT).size();
  }
}
