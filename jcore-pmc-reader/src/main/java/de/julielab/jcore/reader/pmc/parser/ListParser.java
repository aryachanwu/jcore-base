/** 
 * 
 * Copyright (c) 2017, JULIE Lab.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the BSD-2-Clause License
 *
 * Author: 
 * 
 * Description:
 **/
package de.julielab.jcore.reader.pmc.parser;

import de.julielab.jcore.types.List;
import de.julielab.jcore.types.ListItem;
import org.apache.uima.jcas.cas.FSArray;

import java.util.stream.IntStream;

public class ListParser extends DefaultElementParser {

	public ListParser(NxmlDocumentParser nxmlDocumentParser) {
		super(nxmlDocumentParser);
		this.elementName = "list";
	}

	@Override
	protected void parseElement(ElementParsingResult result) throws ElementParsingException {
		super.parseElement(result);
		
		List list = (List) result.getAnnotation();
		java.util.List<ListItem> listItems = result.getSubResultAnnotations(ListItem.class);
		FSArray fsArray = new FSArray(nxmlDocumentParser.cas, listItems.size());
		IntStream.range(0, listItems.size()).forEach(i -> fsArray.set(i, listItems.get(i)));
		list.setItemList(fsArray);
	}

	
	
}
