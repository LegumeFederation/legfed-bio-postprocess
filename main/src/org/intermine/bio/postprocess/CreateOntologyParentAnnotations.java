package org.intermine.bio.postprocess;

/*
 * Copyright (C) 2002-2016 FlyMine, Legume Federation
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.text.DecimalFormat;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.LinkedHashMap;

import org.intermine.metadata.ConstraintOp;

import org.intermine.model.bio.BioEntity;

import org.intermine.model.bio.OntologyAnnotation;
import org.intermine.model.bio.GOAnnotation;
import org.intermine.model.bio.POAnnotation;
import org.intermine.model.bio.TOAnnotation;

import org.intermine.model.bio.OntologyTerm;
import org.intermine.model.bio.GOTerm;
import org.intermine.model.bio.POTerm;
import org.intermine.model.bio.TOTerm;

import org.intermine.objectstore.ObjectStoreException;
import org.intermine.objectstore.ObjectStoreWriter;
import org.intermine.objectstore.query.ConstraintSet;
import org.intermine.objectstore.query.ContainsConstraint;
import org.intermine.objectstore.query.OrderDescending;
import org.intermine.objectstore.query.Query;
import org.intermine.objectstore.query.QueryClass;
import org.intermine.objectstore.query.QueryField;
import org.intermine.objectstore.query.QueryValue;
import org.intermine.objectstore.query.QueryCollectionReference;
import org.intermine.objectstore.query.QueryObjectReference;
import org.intermine.objectstore.query.Results;
import org.intermine.objectstore.query.ResultsRow;
import org.intermine.objectstore.query.SimpleConstraint;

import org.intermine.util.DynamicUtil;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.log4j.Logger;

/**
 * For given ontology annotations, create additional annotations with those ontology terms' parents.
 * This allows one to only specify the deepest ontology term for, say, a QTL, but be able to query the mine for higher-level terms.
 *
 * NOTE: HARD-CODED LIMIT TO TO: terms for now
 *
 * @author Sam Hokin
 */
public class CreateOntologyParentAnnotations {

    private static final Logger LOG = Logger.getLogger(CreateOntologyParentAnnotations.class);
    protected ObjectStoreWriter osw;

    /**
     * Construct with an ObjectStoreWriter, read and write from the same ObjectStore
     * @param osw object store writer
     */
    public CreateOntologyParentAnnotations(ObjectStoreWriter osw) {
        this.osw = osw;
    }

    /**
     * Run the analysis.
     * @throws ObjectStoreException if the objectstore throws an exception
     */
    public void createOntologyParentAnnotations() throws ObjectStoreException, IllegalAccessException {
        
        LOG.info("Querying OntologyAnnotation records and associated terms and parents...");

        Query qOntologyAnnotation = new Query();
        qOntologyAnnotation.setDistinct(true);
        ConstraintSet csOntologyAnnotation = new ConstraintSet(ConstraintOp.AND);

        // 0 OntologyAnnotation
        QueryClass qcOntologyAnnotation = new QueryClass(OntologyAnnotation.class);
        qOntologyAnnotation.addToSelect(qcOntologyAnnotation);
        qOntologyAnnotation.addFrom(qcOntologyAnnotation);

        // 1 OntologyAnnotation.ontologyTerm (needed for parents)
        QueryClass qcOntologyTerm = new QueryClass(OntologyTerm.class);
        qOntologyAnnotation.addToSelect(qcOntologyTerm);
        qOntologyAnnotation.addFrom(qcOntologyTerm);
        QueryObjectReference ontologyTerm = new QueryObjectReference(qcOntologyAnnotation, "ontologyTerm");
        csOntologyAnnotation.addConstraint(new ContainsConstraint(ontologyTerm, ConstraintOp.CONTAINS, qcOntologyTerm));

        // DEV -- term identifier constraint, speeds things up for dev
        // csOntologyAnnotation.addConstraint(new SimpleConstraint(new QueryField(qcOntologyTerm,"identifier"), ConstraintOp.MATCHES, new QueryValue("TO:%")));

        // 2 OntologyAnnotation.ontologyTerm.parents
        QueryClass qcParents = new QueryClass(OntologyTerm.class);
        qOntologyAnnotation.addToSelect(qcParents);
        qOntologyAnnotation.addFrom(qcParents);
        QueryCollectionReference parents = new QueryCollectionReference(qcOntologyTerm, "parents");
        csOntologyAnnotation.addConstraint(new ContainsConstraint(parents, ConstraintOp.CONTAINS, qcParents));

        // set the constraints
        qOntologyAnnotation.setConstraint(csOntologyAnnotation);

        // store the existing subject/term id pairs so we don't duplicate them
        Set<Pair<String,String>> subjectTermSet = new LinkedHashSet<Pair<String,String>>();
        
        // store the subject/parents in a Set of Pairs for insertion
        Set<Pair<BioEntity,OntologyTerm>> subjectParentSet = new LinkedHashSet<Pair<BioEntity,OntologyTerm>>();

        // execute the query
        Results otResults = osw.getObjectStore().execute(qOntologyAnnotation);
        Iterator<?> otIter = otResults.iterator();
        while (otIter.hasNext()) {
            ResultsRow<?> rr = (ResultsRow<?>) otIter.next();
            // result objects
            OntologyAnnotation annotation = (OntologyAnnotation) rr.get(0);
            OntologyTerm term = (OntologyTerm) rr.get(1);
            OntologyTerm parent = (OntologyTerm) rr.get(2);
            BioEntity subject = (BioEntity) annotation.getFieldValue("subject");
            // store the existing annotation in a Set
            String subjectId = (String) subject.getFieldValue("primaryIdentifier");
            String termIdentifier = (String) term.getFieldValue("identifier");
            Pair<String,String> termPair = new MutablePair<String,String>(subjectId,termIdentifier);
            subjectTermSet.add(termPair);
            // store parent pairs in a Set
            Pair<BioEntity,OntologyTerm> parentPair = new MutablePair<BioEntity,OntologyTerm>(subject,parent);
            subjectParentSet.add(parentPair);
        }

        LOG.info("Storing new parent OntologyAnnotation records...");

        // add each parent annotation that isn't already in the database
        osw.beginTransaction();
        for (Pair<BioEntity,OntologyTerm> pair : subjectParentSet) {
            BioEntity subject = pair.getLeft();
            OntologyTerm term = pair.getRight();
            String subjectId = (String) subject.getFieldValue("primaryIdentifier");
            String termIdentifier = (String) term.getFieldValue("identifier");
            String termName = (String) term.getFieldValue("name");
            // store if this pair is NOT already in database
            Pair<String,String> termPair = new MutablePair<String,String>(subjectId,termIdentifier);
            if (!subjectTermSet.contains(termPair)) {
                // HACK: this shouldn't be hardcoded!
                // add annotation subclass -- branch on type
                if (termIdentifier.startsWith("GO:")) {
                    GOAnnotation newAnnotation = (GOAnnotation) DynamicUtil.createObject(Collections.singleton(GOAnnotation.class));
                    newAnnotation.setSubject(subject);
                    newAnnotation.setOntologyTerm(term);
                    osw.store(newAnnotation);
                } else if (termIdentifier.startsWith("PO:")) {
                    POAnnotation newAnnotation = (POAnnotation) DynamicUtil.createObject(Collections.singleton(POAnnotation.class));
                    newAnnotation.setSubject(subject);
                    newAnnotation.setOntologyTerm(term);
                    osw.store(newAnnotation);
                } else if (termIdentifier.startsWith("TO:")) {
                    TOAnnotation newAnnotation = (TOAnnotation) DynamicUtil.createObject(Collections.singleton(TOAnnotation.class));
                    newAnnotation.setSubject(subject);
                    newAnnotation.setOntologyTerm(term);
                    osw.store(newAnnotation);
                } else {
                    LOG.error("Unsupported OntologyTerm "+termIdentifier);
                }
            }
        }
        osw.commitTransaction();

    }

}
