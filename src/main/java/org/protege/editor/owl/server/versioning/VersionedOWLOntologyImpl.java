package org.protege.editor.owl.server.versioning;

import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.VersionedOWLOntology;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.EvictingQueue;

public class VersionedOWLOntologyImpl implements VersionedOWLOntology {

    /*
     * Hidden directory name to store history file and other versioning resources
     */
    public static final String VERSION_DOCUMENT_DIRECTORY = ".owlserver";

    /*
     * Extension for the metadata file
     */
    public static final String VERSION_DOCUMENT_EXTENSION = ".vontology";

    private static final int REVISION_LOG_CACHE_SIZE = 10;

    /*
     * Log cache that stores the last REVISION_LOG_CACHE_SIZE revisions.
     */
    private EvictingQueue<ChangeMetadata> logCache = EvictingQueue.create(REVISION_LOG_CACHE_SIZE);

    private ServerDocument serverDocument;
    private OWLOntology ontology;
    private ChangeHistory localHistory;

    private boolean isHistoryDirty = false;

    public VersionedOWLOntologyImpl(ServerDocument serverDocument, OWLOntology ontology,
            DocumentRevision initialRevision, ChangeHistory localHistory) {
        this.serverDocument = serverDocument;
        this.ontology = ontology;
        this.localHistory = localHistory;
    }

    public VersionedOWLOntologyImpl(ServerDocument serverDocument, OWLOntology ontology) {
        this.serverDocument = serverDocument;
        this.ontology = ontology;
        this.localHistory = ChangeHistoryImpl.createEmptyChangeHistory();
    }

    public static File getMetadataFile(File ontologyFile) {
        File parentDir = getVersioningDirectory(ontologyFile);
        return new File(parentDir, ontologyFile.getName() + VERSION_DOCUMENT_EXTENSION);
    }

    public static HistoryFile getHistoryFile(File ontologyFile) throws InvalidHistoryFileException {
        File parentDir = getVersioningDirectory(ontologyFile);
        File historyFile = new File(parentDir, ontologyFile.getName() + ChangeHistory.CHANGE_DOCUMENT_EXTENSION);
        return HistoryFile.openExisting(historyFile.getAbsolutePath());
    }

    public static File getVersioningDirectory(File ontologyFile) {
        File dir = ontologyFile.getParentFile();
        return new File(dir, VERSION_DOCUMENT_DIRECTORY);
    }

    public static File getBackingStore(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        IRI documentLocation = manager.getOntologyDocumentIRI(ontology);
        if (!documentLocation.getScheme().equals("file")) {
            return null;
        }
        return new File(documentLocation.toURI());
    }

    @Override
    public String getDisplayName() {
        return ontology.getOntologyID().getOntologyIRI().get().toString();
    }

    @Override
    public ServerDocument getServerDocument() {
        return serverDocument;
    }

    @Override
    public OWLOntology getOntology() {
        return ontology;
    }

    @Override
    public ChangeHistory getLocalHistory() {
        return localHistory;
    }

    @Override
    public void addRevision(ChangeMetadata metadata, List<OWLOntologyChange> changes) {
        localHistory.addRevisionBundle(metadata, changes);
        logCache.add(metadata);
    }

    @Override
    public ChangeMetadata getRevisionLog(DocumentRevision revision) {
        return localHistory.getChangeMetadataForRevision(revision);
    }

    @Override
    public List<ChangeMetadata> getLatestRevisionLog(int offset) {
        if (offset > REVISION_LOG_CACHE_SIZE) {
            offset = REVISION_LOG_CACHE_SIZE; // handle until the maximum cache size
        }
        return logCache.stream().limit(offset).collect(Collectors.toList());
    }

    @Override
    public DocumentRevision getStartRevision() {
        return localHistory.getStartRevision();
    }

    @Override
    public DocumentRevision getHeadRevision() {
        return localHistory.getHeadRevision();
    }

    @Override
    public boolean saveMetadata() throws Exception { // TODO Change to saveConfig()?
        File ontologyFile = getBackingStore(ontology);
        if (ontologyFile == null) {
            return false;
        }
        File metadataFile = getMetadataFile(ontologyFile);
        metadataFile.getParentFile().mkdirs();
        ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(metadataFile)));
        try {
            oos.writeObject(serverDocument);
        }
        finally {
            oos.flush();
            oos.close();
        }
        return true;
    }

    @Override
    public boolean saveLocalHistory() throws IOException, InvalidHistoryFileException {
        File ontologyFile = getBackingStore(ontology);
        if (ontologyFile == null) {
            return false;
        }
        HistoryFile historyFile = getHistoryFile(ontologyFile);
        if (isHistoryDirty || !historyFile.exists()) {
            historyFile.getParentFile().mkdirs();
            ChangeHistoryUtils.writeChanges(localHistory, historyFile);
            isHistoryDirty = false;
        }
        return true;
    }

    @Override
    public String toString() {
        File ontologyFile = getBackingStore(ontology);
        String template = "Working ontology: %s\n"
                + "HEAD: %s\n"
                + "Remote host: %s\n";
        return String.format(template, ontologyFile.getAbsolutePath(), getHeadRevision(), serverDocument.getHost());
    }
}
