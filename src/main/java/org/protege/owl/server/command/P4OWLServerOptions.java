package org.protege.owl.server.command;

import org.apache.commons.cli.Option;

public class P4OWLServerOptions {
    private P4OWLServerOptions() {
        ;
    }
    
    public static final Option NEEDS_HELP_OPTION      = new Option("h", "help", false, "prints this help message");
    public static final Option ONTOLOGY_FORMAT_OPTION = new Option("f", "format", true, "set the saved ontology format.");
    public static final Option REVISION_OPTION        = new Option("r", "revision", true, "ontology document revision");
}
