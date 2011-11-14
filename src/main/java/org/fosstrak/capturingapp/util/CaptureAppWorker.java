package org.fosstrak.capturingapp.util;

import org.fosstrak.capturingapp.CaptureApp;
import org.fosstrak.capturingapp.CaptureAppPortTypeImpl;

import java.io.IOException;
import java.util.concurrent.Future;

/**
 * tiny helper to execute a capture application and at the same time maintain
 * a handle to it.
 */
public class CaptureAppWorker {
    // the capture application itself.
    private CaptureApp captureApp;

    // an identifier for this worker/capture application.
    private final String identifier;

    // a handle to the executor service running the capture application.
    @SuppressWarnings("unchecked")
    private Future handle;

    /**
     * creates a new worker.
     *
     * @param identifier an identifier for this worker/capture application.
     * @param cap        a handle to the executor service running the capture application.
     */
    public CaptureAppWorker(String identifier,
                            CaptureApp cap) {

        this.captureApp = cap;
        this.identifier = identifier;
    }

    /**
     * start the execution of the worker.
     */
    public void start() {
        handle = CaptureAppPortTypeImpl.submitToThreadPool(captureApp);
    }

    /**
     * stop the execution of the worker.
     */
    public void stop() {
        try {
            captureApp.stopCaptureApp();
        } catch (IOException e) {
            e.printStackTrace();
        }
        handle.cancel(true);
    }

    /**
     * @return a handle to the capture application.
     */
    public org.fosstrak.capturingapp.CaptureApp getCaptureApp() {
        return captureApp;
    }

    /**
     * @return the identifier for this worker.
     */
    public final String getIdentifier() {
        return identifier;
    }
}