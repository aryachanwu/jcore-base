/**
 * Copyright (c) 2017, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the BSD-2-Clause License
 * <p>
 * Author:
 * <p>
 * Description:
 **/
package de.julielab.jcore.consumer.entityevaluator;

import de.julielab.jcore.utility.index.JCoReTreeMapAnnotationIndex;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.TOP;
import org.apache.uima.jcas.tcas.Annotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SentenceIdColumn extends Column {
    private static final Logger log = LoggerFactory.getLogger(SentenceIdColumn.class);

    private JCoReTreeMapAnnotationIndex<Long, ? extends Annotation> sentenceIndex;
    private Map<Annotation, String> sentenceIds = new HashMap<>();

    public SentenceIdColumn(String documentId, Column c, JCoReTreeMapAnnotationIndex<Long, ? extends Annotation> sentenceIndex) {
        super(c);
        this.sentenceIndex = sentenceIndex;
        // in case the sentences do not have an ID we give them one composed of document ID and sentence number within the document
        if (!sentenceIndex.getIndex().isEmpty()) {
            // we expect that the sentences are non-overlapping and thus ordered strictly ascending by offset
            int sentenceNumber = 0;
            for (Annotation s : sentenceIndex.getIndex().values().stream().flatMap(sentences -> sentences.stream()).collect(Collectors.toList())) {
                String sentenceId = getValue(s, null).peekFirst();
                if (sentenceId == null) {
                    if (documentId == null)
                        throw new IllegalArgumentException("At least one sentence does not have an ID, but the sentence ID column was added for output columns and the document ID column was not defined. But it is required to create a unique sentence ID.");
                    sentenceId = documentId + ":" + sentenceNumber++;
                }
                sentenceIds.put(s, sentenceId);
            }
        }
    }

    public JCoReTreeMapAnnotationIndex<Long, ? extends Annotation> getSentenceIndex() {
        return sentenceIndex;
    }

    @Override
    public Deque<String> getValue(TOP a, JCas aJCas) {
        String value = "";
        if (a != null) {
            Annotation sentence = sentenceIndex.get((Annotation) a);
            if (sentence != null) {
                value = sentenceIds.get(sentence);
            } else {
                log.warn("There was no sentence found covering the annotation " + a);
            }
        }
        Deque<String> ret = new ArrayDeque<>(1);
        if (value != null)
            ret.add(value);
        return ret;
    }

}
