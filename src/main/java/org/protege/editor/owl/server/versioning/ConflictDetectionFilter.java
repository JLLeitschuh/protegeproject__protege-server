package org.protege.editor.owl.server.versioning;

import org.protege.editor.owl.server.api.ChangeService;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.ServerFilterAdapter;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.TransportHandler;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.OWLServerException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.ImportChange;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.Description;
import edu.stanford.protege.metaproject.api.Name;
import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;
import edu.stanford.protege.metaproject.api.ProjectOptions;
import edu.stanford.protege.metaproject.api.UserId;

/**
 * Represents the change document layer that will validate the user changes in the commit document.
 *
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ConflictDetectionFilter extends ServerFilterAdapter {

    private ChangeDocumentPool changePool = new ChangeDocumentPool();

    private ChangeService changeService;

    public ConflictDetectionFilter(ServerLayer delegate) {
        super(delegate);
        changeService = new DefaultChangeService(changePool);
    }

    public void setLoginService(ChangeService changeService) {
        this.changeService = changeService;
    }

    @Override
    public ServerDocument createProject(AuthToken token, ProjectId projectId, Name projectName,
            Description description, UserId owner, Optional<ProjectOptions> options)
            throws AuthorizationException, ServerServiceException {
        ServerDocument serverDocument = super.createProject(token, projectId, projectName, description, owner, options);
        changePool.setChangeDocument(serverDocument.getHistoryFile(), ChangeHistoryImpl.createEmptyChangeHistory());
        return serverDocument;
    }
    
    @Override
    public void commit(AuthToken token, Project project, CommitBundle commits) throws ServerServiceException {
       try {
            List<OWLOntologyChange> conflicts = getConflicts(project, commits);
            if (!conflicts.isEmpty()) {
                throw new ServerServiceException("Conflicts detected: " + conflicts); // TODO: Fix the exception
            }
            super.commit(token, project, commits);
       }
       catch (Exception e) {
           throw new ServerServiceException(e);
       }
    }

    private List<OWLOntologyChange> getConflicts(Project project, CommitBundle commits) throws Exception {
        List<OWLOntologyChange> conflicts = new ArrayList<OWLOntologyChange>();
        OWLOntology cacheOntology = OWLManager.createOWLOntologyManager().createOntology();
        
        List<OWLOntologyChange> clientChanges = commits.getChanges();
        CollectingChangeVisitor collectedClientChanges = CollectingChangeVisitor.collectChanges(clientChanges);
        
        HistoryFile historyFile = HistoryFile.openExisting(project.getFile().getAbsolutePath());
        ChangeHistory allChangeHistory = changeService.getAllChanges(historyFile);
        
        final DocumentRevision headRevision = changeService.getHeadRevision(historyFile);
        DocumentRevision revision = commits.getStartRevision();
        for (; revision.compareTo(headRevision) < 0; revision = revision.next()) {
            ChangeHistory singleRevisionChangeHistory = allChangeHistory.cropChanges(revision, revision.next());
            List<OWLOntologyChange> serverChanges = singleRevisionChangeHistory.getChanges(cacheOntology);
            CollectingChangeVisitor collectedServerChanges = CollectingChangeVisitor.collectChanges(serverChanges);
            computeConflicts(collectedClientChanges, collectedServerChanges, conflicts);
        }
        return conflicts;
    }

    private void computeConflicts(CollectingChangeVisitor clientChanges, CollectingChangeVisitor serverChanges, List<OWLOntologyChange> conflicts) {
        if (clientChanges.getLastOntologyIDChange() != null && serverChanges.getLastOntologyIDChange() != null) {
            conflicts.add(clientChanges.getLastOntologyIDChange());
        }
        for (Entry<OWLImportsDeclaration, ImportChange> entry : clientChanges.getLastImportChangeMap().entrySet()) {
            OWLImportsDeclaration decl = entry.getKey();
            if (serverChanges.getLastImportChangeMap().containsKey(decl)) {
                conflicts.add(entry.getValue());
            }
        }
        for (Entry<OWLAnnotation, OWLOntologyChange> entry : clientChanges.getLastOntologyAnnotationChangeMap().entrySet()) {
            OWLAnnotation annotation = entry.getKey();
            if (serverChanges.getLastOntologyAnnotationChangeMap().containsKey(annotation)) {
                conflicts.add(entry.getValue());
            }
        }
        for (Entry<OWLAxiom, OWLAxiomChange> entry : clientChanges.getLastAxiomChangeMap().entrySet()) {
            OWLAxiom axiom = entry.getKey();
            if (serverChanges.getLastAxiomChangeMap().containsKey(axiom)) {
                conflicts.add(entry.getValue());
            }
        }
    }

    @Override
    public void setTransport(TransportHandler transport) throws OWLServerException {
        try {
            transport.bind(changeService);
        }
        catch (Exception e) {
            throw new OWLServerException(e);
        }
        super.setTransport(transport);
    }
}
