/** 
 * NegativeList.java
 * 
 * Copyright (c) 2008, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: tomanek
 * 
 * Current version: 2.2
 * Since version:   2.2
 *
 * Methods to handle a negative list of entity mentions. 
 **/

package de.julielab.jcore.ae.jnet.uima;

import java.io.*;
import java.util.TreeSet;

public class NegativeList {

	private final static String DELIM = "@";

	private TreeSet<String> negativeList;

	public NegativeList(final File myFile) throws IOException {
		this(new FileInputStream(myFile));
	}
	
	public NegativeList(final InputStream is) throws IOException {
		init(is);
	}

	/**
	 * reads the negative list from a file and stored the entries in a set
	 * 
	 * @param myFile
	 */
	private void init(final InputStream is) throws IOException {
		negativeList = new TreeSet<String>();
		BufferedReader br;
		br = new BufferedReader(new InputStreamReader(is));
		String line = "";
		while ((line = br.readLine()) != null)
			negativeList.add(line);
	}

	/**
	 * checks whether an entity mention is contained in the negative list.
	 * 
	 * @param mentionText
	 *            the text covered by the entity annotation
	 * @param label
	 *            the label assigned to this entity annotation
	 * @return true if negative list contains mentionText (with label)
	 */
	public boolean contains(final String mentionText, final String label) {

		// check with label
		String searchString = mentionText + DELIM + label;
		if (negativeList.contains(searchString))
			return true;

		// check without label
		searchString = mentionText;
		if (negativeList.contains(searchString))
			return true;
		return false;
	}

}
