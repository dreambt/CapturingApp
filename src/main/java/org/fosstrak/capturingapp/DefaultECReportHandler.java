package org.fosstrak.capturingapp;

import org.apache.log4j.Logger;
import org.drools.builder.ResourceType;
import org.drools.io.ResourceFactory;

/**
 * The default handler. This handler gets always loaded. if you invoke the
 * handler with a change-set file, the handler will use this change-set to
 * load the drools rule set.
 */
public class DefaultECReportHandler extends ECReportsHandler {

    // logger
    private static final Logger log = Logger.getLogger(DefaultECReportHandler.class);

    /**
     * default constructor.
     */
    public DefaultECReportHandler() {
        super();
    }

    /**
     * create a new handler with a non default change set.
     *
     * @param changeSet
     */
    public DefaultECReportHandler(String changeSet) {
        super(changeSet);
    }

    @Override
    public void loadRules() {
        // we only load the rules when the rule base is empty.
        if (null == kbase) {
            log.debug("从文件中加载规则.");
            kbuilder.add(
                    ResourceFactory.newClassPathResource(
                            changeSet,
                            DefaultECReportHandler.class),
                    ResourceType.CHANGE_SET);
        }
    }
}