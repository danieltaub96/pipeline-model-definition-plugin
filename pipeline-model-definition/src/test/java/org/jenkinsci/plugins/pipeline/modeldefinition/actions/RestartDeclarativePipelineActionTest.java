/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.modeldefinition.actions;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSelect;
import hudson.model.BooleanParameterValue;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.SecurityRealm;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.jenkinsci.plugins.pipeline.StageStatus;
import org.jenkinsci.plugins.pipeline.modeldefinition.AbstractModelDefTest;
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils;
import org.jenkinsci.plugins.pipeline.modeldefinition.causes.RestartDeclarativePipelineCause;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectTest;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.jenkinsci.plugins.pipeline.modeldefinition.BasicModelDefTest.stageStatusPredicate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class RestartDeclarativePipelineActionTest extends AbstractModelDefTest {

    @Issue("JENKINS-45455")
    @Test
    public void actionPresentOnDeclarative() throws Exception {
        WorkflowRun r = expect("simplePipeline").go();

        assertNotNull(r.getAction(RestartDeclarativePipelineAction.class));
    }

    @Issue("JENKINS-45455")
    @Test
    public void restartDisabled() throws Exception {
        SecurityRealm originalRealm = j.jenkins.getSecurityRealm();
        AuthorizationStrategy originalStrategy = j.jenkins.getAuthorizationStrategy();

        try {
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            MockAuthorizationStrategy auth = new MockAuthorizationStrategy()
                    .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                    .grant(Item.BUILD).everywhere().to("dev1")
                    .grant(Jenkins.READ).everywhere().to("dev1", "dev2");
            j.jenkins.setAuthorizationStrategy(auth);
            WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "restartDisabled");
            p.setDefinition(new CpsFlowDefinition(
                    "pipeline {\n" +
                            "  agent any\n" +
                            "  stages {\n" +
                            "    stage('before') {\n" +
                            "      steps {\n" +
                            "        semaphore 'wait'\n" +
                            "      }\n" +
                            "    }\n" +
                            "  }\n" +
                            "}\n", true));

            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);

            RestartDeclarativePipelineAction action = b.getAction(RestartDeclarativePipelineAction.class);
            assertNotNull(action);

            assertFalse(action.isRestartEnabled());
            SemaphoreStep.success("wait/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(b));

            assertTrue(action.isRestartEnabled());
            assertTrue(canRestart(b, "admin"));
            assertTrue(canRestart(b, "dev1"));
            assertFalse(canRestart(b, "dev2"));

            p.setDisabled(true);
            assertFalse(canRestart(b, "admin1"));
            assertFalse(canRestart(b, "dev1"));
            assertFalse(canRestart(b, "dev2"));
        } finally {
            j.jenkins.setSecurityRealm(originalRealm);
            j.jenkins.setAuthorizationStrategy(originalStrategy);
        }
    }

    private static boolean canRestart(WorkflowRun b, String user) {
        final RestartDeclarativePipelineAction a = b.getAction(RestartDeclarativePipelineAction.class);
        return ACL.impersonate(User.get(user).impersonate(), new NotReallyRoleSensitiveCallable<Boolean,RuntimeException>() {
            @Override public Boolean call() throws RuntimeException {
                return a.isRestartEnabled();
            }
        });
    }

    @Issue("JENKINS-45455")
    @Test
    public void simpleRestart() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "simpleRestart")
                .logContains("Odd numbered build, failing",
                        "This shouldn't show up on second run")
                .go();

        FlowExecution firstExecution = original.getExecution();
        assertNotNull(firstExecution);
        assertNotNull(firstExecution.getCauseOfFailure());

        // skip-on-restart didn't fail at all.
        List<FlowNode> firstRunFirstStageNodes = Utils.findStageFlowNodes("skip-on-restart", firstExecution);
        assertNotNull(firstRunFirstStageNodes);
        assertFalse(firstRunFirstStageNodes.isEmpty());
        FlowNode firstRunFirstStageStart = firstRunFirstStageNodes.get(0);
        assertNull(firstRunFirstStageStart.getAction(NotExecutedNodeAction.class));
        assertFalse(stageStatusPredicate("skip-on-restart", StageStatus.getSkippedForRestart()).apply(firstRunFirstStageStart));
        assertFalse(stageStatusPredicate("skip-on-restart", StageStatus.getSkippedForFailure()).apply(firstRunFirstStageStart));
        assertFalse(stageStatusPredicate("skip-on-restart", StageStatus.getFailedAndContinued()).apply(firstRunFirstStageStart));

        // restart failed.
        List<FlowNode> firstRunSecondStageNodes = Utils.findStageFlowNodes("restart", firstExecution);
        assertNotNull(firstRunSecondStageNodes);
        assertFalse(firstRunSecondStageNodes.isEmpty());
        FlowNode firstRunSecondStageStart = firstRunSecondStageNodes.get(0);
        assertNull(firstRunSecondStageStart.getAction(NotExecutedNodeAction.class));
        assertFalse(stageStatusPredicate("restart", StageStatus.getSkippedForRestart()).apply(firstRunSecondStageStart));
        assertFalse(stageStatusPredicate("restart", StageStatus.getSkippedForFailure()).apply(firstRunSecondStageStart));
        assertTrue(stageStatusPredicate("restart", StageStatus.getFailedAndContinued()).apply(firstRunSecondStageStart));

        // post-restart skipped due to failure.
        List<FlowNode> firstRunThirdStageNodes = Utils.findStageFlowNodes("post-restart", firstExecution);
        assertNotNull(firstRunThirdStageNodes);
        assertFalse(firstRunThirdStageNodes.isEmpty());
        FlowNode firstRunThirdStageStart = firstRunThirdStageNodes.get(0);
        assertNull(firstRunThirdStageStart.getAction(NotExecutedNodeAction.class));
        assertFalse(stageStatusPredicate("post-restart", StageStatus.getSkippedForRestart()).apply(firstRunThirdStageStart));
        assertTrue(stageStatusPredicate("post-restart", StageStatus.getSkippedForFailure()).apply(firstRunThirdStageStart));
        assertFalse(stageStatusPredicate("post-restart", StageStatus.getFailedAndContinued()).apply(firstRunThirdStageStart));

        WorkflowJob p = original.getParent();

        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        // We should be able to restart skip-on-restart and restart, but not post-restart
        List<String> restartableStages = action.getRebuildableStages();
        assertThat(restartableStages, is(Arrays.asList("skip-on-restart", "restart")));

        HtmlPage redirect = restartFromStageInUI(original, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("Even numbered build, success", b2);
        j.assertLogContains("Stage \"skip-on-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Now we're post-restart", b2);
        j.assertLogNotContains("This shouldn't show up on second run", b2);

        RestartDeclarativePipelineCause cause = b2.getCause(RestartDeclarativePipelineCause.class);
        assertNotNull(cause);
        assertEquals(original, cause.getOriginal());
        assertEquals("restart", cause.getOriginStage());
        // Note that I'm using the fully qualified name for the cause Messages class so that I don't have to change things
        // if I ever want to test something from other Messages.
        assertEquals(org.jenkinsci.plugins.pipeline.modeldefinition.causes.Messages.RestartedDeclarativePipelineCause_ShortDescription(1, "restart"),
                cause.getShortDescription());

        FlowExecution secondExecution = b2.getExecution();
        assertNotNull(secondExecution);
        assertNull(secondExecution.getCauseOfFailure());

        // skip-on-restart was skipped for restart.
        List<FlowNode> secondRunFirstStageNodes = Utils.findStageFlowNodes("skip-on-restart", secondExecution);
        assertNotNull(secondRunFirstStageNodes);
        assertFalse(secondRunFirstStageNodes.isEmpty());
        FlowNode secondRunFirstStageStart = secondRunFirstStageNodes.get(0);
        assertNotNull(secondRunFirstStageStart.getAction(NotExecutedNodeAction.class));
        assertTrue(stageStatusPredicate("skip-on-restart", StageStatus.getSkippedForRestart()).apply(secondRunFirstStageStart));
        assertFalse(stageStatusPredicate("skip-on-restart", StageStatus.getSkippedForFailure()).apply(secondRunFirstStageStart));
        assertFalse(stageStatusPredicate("skip-on-restart", StageStatus.getFailedAndContinued()).apply(secondRunFirstStageStart));

        // restart passed this time and was not skipped.
        List<FlowNode> secondRunSecondStageNodes = Utils.findStageFlowNodes("restart", secondExecution);
        assertNotNull(secondRunSecondStageNodes);
        assertFalse(secondRunSecondStageNodes.isEmpty());
        FlowNode secondRunSecondStageStart = secondRunSecondStageNodes.get(0);
        assertNull(secondRunSecondStageStart.getAction(NotExecutedNodeAction.class));
        assertFalse(stageStatusPredicate("restart", StageStatus.getSkippedForRestart()).apply(secondRunSecondStageStart));
        assertFalse(stageStatusPredicate("restart", StageStatus.getSkippedForFailure()).apply(secondRunSecondStageStart));
        assertFalse(stageStatusPredicate("restart", StageStatus.getFailedAndContinued()).apply(secondRunSecondStageStart));

        // post-restart ran fine.
        List<FlowNode> secondRunThirdStageNodes = Utils.findStageFlowNodes("post-restart", secondExecution);
        assertNotNull(secondRunThirdStageNodes);
        assertFalse(secondRunThirdStageNodes.isEmpty());
        FlowNode secondRunThirdStageStart = secondRunThirdStageNodes.get(0);
        assertNull(secondRunThirdStageStart.getAction(NotExecutedNodeAction.class));
        assertFalse(stageStatusPredicate("post-restart", StageStatus.getSkippedForRestart()).apply(secondRunThirdStageStart));
        assertFalse(stageStatusPredicate("post-restart", StageStatus.getSkippedForFailure()).apply(secondRunThirdStageStart));
        assertFalse(stageStatusPredicate("post-restart", StageStatus.getFailedAndContinued()).apply(secondRunThirdStageStart));
    }

    @Issue("JENKINS-45455")
    @Test
    public void notPresentStageName() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "simpleRestart")
                .logContains("Odd numbered build, failing",
                        "This shouldn't show up on second run")
                .go();

        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        Exception runFailure = null;
        try {
            Queue.Item queueItem = action.run("not-present");
            // Because we should definitely have thrown an exception in run.
            assertNull(queueItem);
        } catch (Exception e) {
            runFailure = e;
        }
        assertNotNull(runFailure);

        assertTrue(runFailure instanceof IllegalStateException);
        assertEquals(Messages.RestartDeclarativePipelineAction_StageNameNotPresent("not-present", original.getFullDisplayName()),
                runFailure.getMessage());
    }

    @Issue("JENKINS-45455")
    @Test
    public void emptyStageName() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "simpleRestart")
                .logContains("Odd numbered build, failing",
                        "This shouldn't show up on second run")
                .go();

        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        Exception runFailure = null;
        try {
            Queue.Item queueItem = action.run("");
            // Because we should definitely have thrown an exception in run.
            assertNull(queueItem);
        } catch (Exception e) {
            runFailure = e;
        }
        assertNotNull(runFailure);

        assertTrue(runFailure instanceof IllegalStateException);
        assertEquals(Messages.RestartDeclarativePipelineAction_NullStageName(), runFailure.getMessage());
    }

    @Issue("JENKINS-45455")
    @Test
    public void nullStageName() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "simpleRestart")
                .logContains("Odd numbered build, failing",
                        "This shouldn't show up on second run")
                .go();

        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        Exception runFailure = null;
        try {
            Queue.Item queueItem = action.run(null);
            // Because we should definitely have thrown an exception in run.
            assertNull(queueItem);
        } catch (Exception e) {
            runFailure = e;
        }
        assertNotNull(runFailure);

        assertTrue(runFailure instanceof IllegalStateException);
        assertEquals(Messages.RestartDeclarativePipelineAction_NullStageName(), runFailure.getMessage());
    }

    @Issue("JENKINS-45455")
    @Test
    public void projectNotBuildable() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "simpleRestart")
                .logContains("Odd numbered build, failing",
                        "This shouldn't show up on second run")
                .go();

        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        WorkflowJob p = original.getParent();
        p.setDisabled(true);

        Exception runFailure = null;
        try {
            Queue.Item queueItem = action.run("restart");
            // Because we should definitely have thrown an exception in run.
            assertNull(queueItem);
        } catch (Exception e) {
            runFailure = e;
        }
        assertNotNull(runFailure);

        assertTrue(runFailure instanceof IllegalStateException);
        assertEquals(Messages.RestartDeclarativePipelineAction_ProjectNotBuildable(p.getFullName()),
                runFailure.getMessage());
    }

    @Issue("JENKINS-45455")
    @Test
    public void originStillRunning() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "originStillRunning");
        p.setDefinition(new CpsFlowDefinition(
                "pipeline {\n" +
                        "  agent any\n" +
                        "  stages {\n" +
                        "    stage('before') {\n" +
                        "      steps {\n" +
                        "        semaphore 'waitAgain'\n" +
                        "      }\n" +
                        "    }\n" +
                        "    stage('after') {\n" +
                        "      steps {\n" +
                        "        echo 'After'\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n", true));

        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("waitAgain/1", b);

        RestartDeclarativePipelineAction action = b.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertFalse(action.isRestartEnabled());

        Exception runFailure = null;
        try {
            Queue.Item queueItem = action.run("after");
            // Because we should definitely have thrown an exception in run.
            assertNull(queueItem);
        } catch (Exception e) {
            runFailure = e;
        }
        assertNotNull(runFailure);

        assertTrue(runFailure instanceof IllegalStateException);
        assertEquals(Messages.RestartDeclarativePipelineAction_OriginRunIncomplete(b.getFullDisplayName()), runFailure.getMessage());

        SemaphoreStep.success("waitAgain/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertTrue(action.isRestartEnabled());
    }

    @Issue("JENKINS-45455")
    @Test
    public void parametersAndRestart() throws Exception {
        // Initial build passes - we mainly just want it to add the parameters.
        WorkflowRun original = expect("restart", "parametersAndRestart").go();

        WorkflowJob p = original.getParent();

        WorkflowRun failing = p.scheduleBuild2(0, new ParametersAction(new BooleanParameterValue("flag", false),
                new StringParameterValue("someParam", "changed"))).waitForStart();

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(failing));

        HtmlPage redirect = restartFromStageInUI(failing, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b3 = p.getBuildByNumber(3);
        assertNotNull(b3);
        j.assertBuildStatusSuccess(b3);
        j.assertLogContains("Odd numbered build, success", b3);
        j.assertLogContains("Stage \"pre-restart\" skipped due to this build restarting at stage \"restart\"", b3);
        j.assertLogContains("Flag is false", b3);
        j.assertLogContains("someParam is changed", b3);
        j.assertLogContains("otherParam is should not change", b3);
        j.assertLogNotContains("hello on non-restart", b3);
    }

    @Issue("JENKINS-45455")
    @Test
    public void sameJenkinsfileNonMultibranch() throws Exception {
        // This test verifies that the Jenkinsfile is the same across both builds, even if the SCM has changed.
        // Since we're not a multibranch here, the actual checkout does change, though.

        WorkflowRun original = expect(Result.FAILURE, "restart", "sameJenkinsfileNonMultibranch").go();

        WorkflowJob p = original.getParent();

        // Make SCM changes now.
        sampleRepo.write("Jenkinsfile", pipelineSourceFromResources("restart/sameJenkinsfileNonMultibranchSecond"));
        sampleRepo.write("newFile", "exists");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "newFile");
        sampleRepo.git("commit", "--message=later");

        HtmlPage redirect = restartFromStageInUI(original, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("Even numbered build, success", b2);
        j.assertLogContains("Stage \"skip-on-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogNotContains("Now we're post-restart", b2);
        j.assertLogNotContains("hello on non-restart", b2);
        j.assertLogContains("Stage \"post-restart\" skipped due to when conditional", b2);
    }

    @Issue("JENKINS-45455")
    @Test
    public void sameCheckoutMultibranch() throws Exception {
        prepRepoWithJenkinsfile("restart", "sameCheckoutMultibranch");
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "sameCheckoutParent");
        mp.getSourcesList().add(new BranchSource(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", false)));
        WorkflowJob p = WorkflowMultiBranchProjectTest.scheduleAndFindBranchProject(mp, "master");
        j.waitUntilNoActivity();
        WorkflowRun b1 = p.getLastBuild();
        assertNotNull(b1);
        assertEquals(1, b1.getNumber());
        j.assertBuildStatus(Result.FAILURE, b1);

        // Make SCM changes now.
        sampleRepo.write("Jenkinsfile", pipelineSourceFromResources("restart/sameCheckoutMultibranchSecond"));
        sampleRepo.write("newFile", "exists");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("add", "newFile");
        sampleRepo.git("commit", "--message=later");

        HtmlPage redirect = restartFromStageInUI(b1, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("Even numbered build, success", b2);
        j.assertLogContains("Stage \"skip-on-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Now we're post-restart", b2);
        j.assertLogNotContains("hello on non-restart", b2);
        j.assertLogNotContains("Stage \"post-restart\" skipped due to when conditional", b2);
    }

    @Issue("JENKINS-45455")
    @Test
    public void stashAndRestart() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "stashAndRestart").go();

        WorkflowJob p = original.getParent();

        HtmlPage redirect = restartFromStageInUI(original, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("Even numbered build, success", b2);
        j.assertLogContains("Stage \"pre-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("someFile is some text", b2);
    }

    @Issue("JENKINS-45455")
    @Test
    public void nestedStagesSkippedOnRestart() throws Exception {
        WorkflowRun original = expect(Result.FAILURE, "restart", "nestedStagesSkippedOnRestart").go();

        WorkflowJob p = original.getParent();

        HtmlPage redirect = restartFromStageInUI(original, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("Even numbered build, success", b2);
        j.assertLogContains("Stage \"parallel-pre-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Stage \"first-parallel-skipped\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Stage \"second-parallel-skipped\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Stage \"sequence-pre-restart\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Stage \"first-sequence-skipped\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Stage \"second-sequence-skipped\" skipped due to this build restarting at stage \"restart\"", b2);
        j.assertLogContains("Made it to post-restart", b2);
    }

    @Issue("JENKINS-45455")
    @Test
    public void changelogChangesetAndRestart() throws Exception {
        prepRepoWithJenkinsfile("restart", "changelogChangesetAndRestart");
        WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "changelogChangesetParent");
        mp.getSourcesList().add(new BranchSource(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", false)));
        WorkflowJob p = WorkflowMultiBranchProjectTest.scheduleAndFindBranchProject(mp, "master");
        j.waitUntilNoActivity();
        // First build is just to establish the baseline.
        WorkflowRun original = p.getLastSuccessfulBuild();
        assertNotNull(original);

        // Make a commit
        sampleRepo.write("newFile", "exists");
        sampleRepo.git("add", "newFile");
        sampleRepo.git("commit", "--message=tada");

        WorkflowRun failing = p.scheduleBuild2(0).waitForStart();

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(failing));

        assertNotNull(failing.getChangeSets());
        assertFalse(failing.getChangeSets().isEmpty());
        assertEquals(1, failing.getChangeSets().size());
        ChangeLogSet<? extends ChangeLogSet.Entry> failingChanges = failing.getChangeSets().get(0);
        assertEquals(1, failingChanges.getItems().length);
        ChangeLogSet.Entry failingEntry = failingChanges.iterator().next();
        assertNotNull(failingEntry);

        // Make another commit
        sampleRepo.write("otherNewFile", "exists");
        sampleRepo.git("add", "otherNewFile");
        sampleRepo.git("commit", "--message=poof");

        HtmlPage redirect = restartFromStageInUI(failing, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b3 = p.getBuildByNumber(3);
        assertNotNull(b3);
        j.assertBuildStatusSuccess(b3);
        j.assertLogContains("Odd numbered build, success", b3);
        j.assertLogContains("Stage \"skip-on-restart\" skipped due to this build restarting at stage \"restart\"", b3);
        j.assertLogContains("Now we're post-restart", b3);
        j.assertLogNotContains("This shouldn't show up on third run", b3);

        assertNotNull(b3.getChangeSets());
        assertFalse(b3.getChangeSets().isEmpty());
        assertEquals(1, b3.getChangeSets().size());
        ChangeLogSet<? extends ChangeLogSet.Entry> b3Changes = failing.getChangeSets().get(0);
        assertEquals(1, b3Changes.getItems().length);
        ChangeLogSet.Entry b3Entry = b3Changes.iterator().next();
        assertNotNull(b3Entry);

        assertEquals(failingEntry.getCommitId(), b3Entry.getCommitId());
    }

    @Issue("JENKINS-45455")
    @Test
    public void isRestartedRunCondition() throws Exception {
        WorkflowRun original = expect("restart", "isRestartedRunCondition")
                .logContains("This shouldn't show up on second run")
                .logNotContains("This should only run on restart")
                .go();

        WorkflowJob p = original.getParent();

        HtmlPage redirect = restartFromStageInUI(original, "restart");

        assertNotNull(redirect);
        assertEquals(p.getAbsoluteUrl(), redirect.getUrl().toString());

        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getBuildByNumber(2);
        assertNotNull(b2);
        j.assertBuildStatusSuccess(b2);
        j.assertLogContains("This should only run on restart", b2);
        j.assertLogNotContains("This shouldn't show up on second run", b2);
    }

    private HtmlPage restartFromStageInUI(@Nonnull WorkflowRun original, @Nonnull String stageName) throws Exception {
        RestartDeclarativePipelineAction action = original.getAction(RestartDeclarativePipelineAction.class);
        assertNotNull(action);
        assertTrue(action.isRestartEnabled());

        HtmlPage page = j.createWebClient().getPage(original, action.getUrlName());
        HtmlForm form = page.getFormByName("restart");
        HtmlSelect select = form.getSelectByName("stageName");
        select.getOptionByValue(stageName).setSelected(true);
        return j.submit(form);
    }
}
