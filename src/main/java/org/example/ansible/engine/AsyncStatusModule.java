package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.graalvm.polyglot.Context;

import java.util.Map;

/**
 * Implementation of the async_status module.
 */
public class AsyncStatusModule implements Module {

    private final AsyncJobManager asyncJobManager;

    public AsyncStatusModule(AsyncJobManager asyncJobManager) {
        this.asyncJobManager = asyncJobManager;
    }

    @Override
    public TaskResult execute(Map<String, Object> args, BecomeContext becomeContext, Context context) {
        String jid = (String) args.get("jid");
        if (jid == null) {
            jid = (String) args.get("_raw_params");
        }

        if (jid == null) {
            return TaskResult.failure("async_status requires a 'jid' argument");
        }

        AsyncJob job = asyncJobManager.getJob(jid);
        if (job == null) {
            return TaskResult.failure("could not find job " + jid);
        }

        Map<String, Object> data = new java.util.HashMap<>(job.result());
        data.put("ansible_job_id", job.ansibleJobId());
        data.put("started", job.started());
        data.put("finished", job.finished());
        data.put("results_file", job.resultsFile());

        boolean success = job.finished() == 0 || !Boolean.TRUE.equals(data.get("failed"));
        boolean changed = Boolean.TRUE.equals(data.get("changed"));

        return new TaskResult(success, changed, (String) data.get("msg"), data);
    }
}
