package org.fosstrak.capturingapp;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.fosstrak.ale.util.DeserializerUtil;
import org.fosstrak.ale.xsd.ale.epcglobal.ECReports;
import org.fosstrak.epcis.captureclient.CaptureClient;
import org.fosstrak.epcis.model.EPCISDocumentType;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * the capture application retrieves an ECReports from a specified socket. Then
 * a set of registered handlers get invoked with the ECReports returning a
 * simple EPCIS document. The capture application relays the EPCIS document to
 * the EPCIS repository.
 */
public class CaptureApp implements Runnable {

    // the port where the capture application is listening.
    private int port = -1;

    // the URL where the EPCIS can be called.
    private String epcisRepositoryURL = null;

    // the EPCIS capture client.
    private CaptureClient client = null;

    // execute the capture app.
    private boolean execute = true;

    // logger
    private static final Logger log = Logger.getLogger(CaptureApp.class);

    // server socket accepting incoming reports.
    private ServerSocket ss = null;

    // a queue holding the received reports.
    private ConcurrentLinkedQueue<ECReports> reports =
            new ConcurrentLinkedQueue<ECReports>();

    // the ECReport handlers.
    private ConcurrentLinkedQueue<ECReportsHandler> handlers =
            new ConcurrentLinkedQueue<ECReportsHandler>();

    // the EPCIS documents.
    private ConcurrentLinkedQueue<EPCISDocumentType> epcisDocs =
            new ConcurrentLinkedQueue<EPCISDocumentType>();

    // the reports queue worker.
    private Thread reportsQueueWorker = null;

    // the EPCIS documents worker.
    private Thread epcisQueueWorker = null;

    // flag whether capture app is up and running.
    private boolean up = false;

    /**
     * construct a new capture application.
     *
     * @param port               the port where to listen for incoming ECReports.
     * @param epcisRepositoryURL the URL where to call the EPCIS.
     */
    public CaptureApp(int port, String epcisRepositoryURL) {
        this.setPort(port);
        this.setEpcisRepositoryURL(epcisRepositoryURL);
    }

    /**
     * construct a new capture application.
     *
     * @param port        the port where to listen for incoming ECReports.
     * @param epcisClient the EPCIS capture client.
     */
    public CaptureApp(int port, CaptureClient epcisClient) {
        this.setPort(port);
        this.client = epcisClient;
    }

    /**
     * stops the execution of the capture app.
     */
    public void stopCaptureApp() throws IOException {
        this.execute = false;
        reportsQueueWorker.interrupt();
        epcisQueueWorker.interrupt();
        ss.close();
    }

    /**
     * @return true if capture application is up.
     */
    public boolean isUp() {
        return up;
    }

    /**
     * @return true if capture application is executing.
     */
    public boolean isExecuting() {
        return execute;
    }

    /**
     * handles incoming ECReports.
     *
     * @param reports the ECReports.
     */
    private void handleReports(ECReports reports) {
        log.debug("处理传入的报告");
        synchronized (this.reports) {
            this.reports.add(reports);
            this.reports.notifyAll();
        }
    }

    /**
     * register a handler for ECReports.
     *
     * @param handler the handler for the ECReport.
     */
    public void registerHandler(ECReportsHandler handler) {
        synchronized (handlers) {
            handlers.add(handler);
        }
    }

    /**
     * removes a handler.
     *
     * @param handler the handler
     */
    public void deregisterHandler(ECReportsHandler handler) {
        synchronized (handlers) {
            handlers.remove(handler);
        }
    }

    public void run() {
        if ((null == client) && (null == getEpcisRepositoryURL())) {
            log.error("EPCIS 库参数丢失");
            throw new RuntimeException("EPCIS 库参数丢失");
        }
        if (null == client) {
            client = new CaptureClient(getEpcisRepositoryURL());
        }

        // queue worker...
        reportsQueueWorker = new Thread(new Runnable() {
            public void run() {
                while (execute) {
                    try {
                        ECReports r = null;
                        synchronized (reports) {
                            while (0 == reports.size()) {
                                reports.wait();
                            }
                            // remove the first report to work on.
                            r = reports.remove();
                        }
                        synchronized (handlers) {
                            for (ECReportsHandler handler : handlers) {
                                try {
                                    // retrieve the EPCIS document
                                    LinkedList<EPCISDocumentType> docs =
                                            handler.handle(
                                                    r);

                                    if (null != docs) {
                                        // add it to the queue
                                        synchronized (epcisDocs) {
                                            for (EPCISDocumentType doc : docs) {
                                                if (null != doc) {
                                                    epcisDocs.add(doc);
                                                }
                                            }
                                            epcisDocs.notifyAll();
                                        }
                                    }
                                } catch (Exception ex) {
                                    log.debug("处理程序触发的异常." +
                                            ex.getMessage());
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        log.debug("接收到中断.");
                    }
                }
                log.info("停止消费队列.");
            }
        });
        reportsQueueWorker.start();

        // EPCIS documents queue worker...
        epcisQueueWorker = new Thread(new Runnable() {
            public void run() {
                while (execute) {
                    try {
                        EPCISDocumentType doc = null;
                        synchronized (epcisDocs) {
                            while (0 == epcisDocs.size()) {
                                epcisDocs.wait();
                            }
                            // remove the first report to work on.
                            doc = epcisDocs.remove();
                        }
                        try {
                            int httpResponseCode = client.capture(doc);
                            if (httpResponseCode != 200) {
                                log.error("该事件无法被捕获!");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } catch (InterruptedException e) {
                        log.debug("接收到中断.");
                    }
                }
                log.info("停止消费队列.");
            }
        });
        epcisQueueWorker.start();

        try {
            log.debug(String.format("绑定 CaptureApp 到端口 %d", getPort()));
            ss = new ServerSocket(getPort());
            up = true;
            while (execute) {
                try {
                    Socket s = ss.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));

                    String data = in.readLine();
                    // ignore the HTTP header
                    data = in.readLine();
                    data = in.readLine();
                    data = in.readLine();
                    data = in.readLine();

                    StringBuffer buffer = new StringBuffer();
                    while (null != data) {
                        buffer.append(data);
                        data = in.readLine();
                    }
                    log.debug(buffer.toString());

                    // create a stream from the buffer
                    InputStream parseStream = new ByteArrayInputStream(
                            buffer.toString().getBytes());

                    // parse the string
                    ECReports reports = DeserializerUtil
                            .deserializeECReports(parseStream);
                    if (null != reports) {
                        handleReports(reports);
                    }
                } catch (Exception e) {
                    log.error(String.format("不能接收报告: %s",
                            e.getMessage()));
                }
            }
        } catch (IOException bindException) {
            log.error(String.format("不能绑定 CaptureApp: %s",
                    bindException.getMessage()));
        }
        ss = null;
        execute = false;
        up = false;
    }


    /**
     * @param port the port to set
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * @return the port
     */
    public int getPort() {
        return port;
    }

    /**
     * @param epcisRepositoryURL the epcisRepositoryURL to set
     */
    public void setEpcisRepositoryURL(String epcisRepositoryURL) {
        this.epcisRepositoryURL = epcisRepositoryURL;
    }

    /**
     * @return the epcisRepositoryURL
     */
    public String getEpcisRepositoryURL() {
        return epcisRepositoryURL;
    }

    /**
     * starts the CaptureApp in event sink mode (means no relay to EPCIS).
     *
     * @param args the first command line parameter is the TCP port. if omitted port 9999 is used.
     */
    public static void main(String[] args) {
        CaptureApp client;
        int port;
        // check if args[0] is tcp-port
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
            client = new CaptureApp(port, "dummy");
        } else {
            return;
        }

        // register the say hello handler
        client.registerHandler(new DefaultECReportHandler());

        // configure Logger with properties file
        PropertyConfigurator.configure(
                CaptureApp.class.getResource("/log4j.properties"));

        new Thread(client).start();
        try {
            synchronized (CaptureApp.class) {
                CaptureApp.class.wait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("正常退出");
    }
}