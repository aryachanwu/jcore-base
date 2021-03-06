/**
 * Copyright (c) 2015, JULIE Lab.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the BSD-2-Clause License
 */

package de.julielab.jcore.consumer.bionlpformat.utils;

import de.julielab.jcore.types.Gene;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.jcas.JCas;
import org.junit.Before;
import org.junit.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import static org.easymock.classextension.EasyMock.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProteinWriterTest {

	private JCas cas;
	private ProteinWriter proteinWriter;
	private Gene protein1;
	private Gene protein2;
	private Gene protein3;
	private Writer writer;
	
	private final static String PROTEIN_T3 = "T3	Protein 34 39	STAT6\n";
	private final static String PROTEIN_T4 = "T4	Protein 43 56	interleukin 4\n";
	private final static String PROTEIN_T6 = "T6	Protein 88 94	SOCS-1\n";
	private static final String DOCUMENT_TEXT = "Interferons inhibit activation of STAT6 by interleukin 4 in human monocytes by inducing SOCS-1 gene expression.\n" + 
												"Interferons (IFNs) inhibit induction by IL-4 of multiple genes in human monocytes. However, the mechanism by which IFNs mediate this inhibition has not been defined. IL-4 activates gene expression by inducing tyrosine phosphorylation, homodimerization, and nuclear translocation of the latent transcription factor, STAT6 (signal transducer and activator of transcription-6). STAT6-responsive elements are characteristically present in the promoters of IL-4-inducible genes. Because STAT6 activation is essential for IL-4-induced gene expression, we examined the ability of type I and type II IFNs to regulate activation of STAT6 by IL-4 in primary human monocytes. Pretreatment of monocytes with IFN-beta or IFN-gamma, but not IL-1, IL-2, macrophage colony-stimulating factor, granulocyte/macrophage colony-stimulating factor, IL-6, or transforming growth factor beta suppressed activation of STAT6 by IL-4. This inhibition was associated with decreased tyrosine phosphorylation and nuclear translocation of STAT6 and was not evident unless the cells were preincubated with IFN for at least 1 hr before IL-4 stimulation. Furthermore, inhibition by IFN could be blocked by cotreatment with actinomycin D and correlated temporally with induction of the JAK/STAT inhibitory gene, SOCS-1. Forced expression of SOCS-1 in a macrophage cell line, RAW264, markedly suppressed trans-activation of an IL-4-inducible reporter as well as IL-6- and IFN-gamma-induced reporter gene activity. These findings demonstrate that IFNs inhibit IL-4-induced activation of STAT6 and STAT6-dependent gene expression, at least in part, by inducing expression of SOCS-1.";

	@Before
	public void setUp() throws Exception{
		cas = JCasFactory.createJCas("src/test/resources/types/jcore-semantics-biology-types");
		
		protein1 = new Gene(cas);
		protein1.setId("T3");
		protein1.setBegin(34);
		protein1.setEnd(39);
		protein1.addToIndexes();
		protein1.setSpecificType("protein");
		
		protein2 = new Gene(cas);
		protein2.setId("T4");
		protein2.setBegin(43);
		protein2.setEnd(56);
		protein2.addToIndexes();
		protein2.setSpecificType("protein");
		
		protein3 = new Gene(cas);
		protein3.setId("T6");
		protein3.setBegin(88);
		protein3.setEnd(94);
		protein3.addToIndexes();
		protein3.setSpecificType("protein");
		
		writer = createMock(FileWriter.class);
		proteinWriter = new ProteinWriter(writer, DOCUMENT_TEXT);
	}
	
	@Test 
	public void testWriteProteinsToFile() throws Exception{
		writer.write(PROTEIN_T3);
		writer.write(PROTEIN_T4);
		writer.write(PROTEIN_T6);
		
		replay(writer);
		
		proteinWriter.writeProtein(protein1);
		proteinWriter.writeProtein(protein2);
		proteinWriter.writeProtein(protein3);
		
		verify(writer);
	}
	
	@Test
	public void testIsWritten() throws Exception{
		writer.write(PROTEIN_T3);
		replay(writer);

		assertFalse(proteinWriter.isWritten(protein1));
		proteinWriter.writeProtein(protein1);
		assertTrue(proteinWriter.isWritten(protein1));
	}	
	@Test
	public void testClose() throws IOException{
		writer.close();
		replay(writer);
		
		proteinWriter.close();
		verify(writer);
	}

}
