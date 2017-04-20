/** 
 * JulesTools.java
 * 
 * Copyright (c) 2006, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 * 
 * Author: muehlhausen
 * 
 * Current version: 1.3	
 * Since version:   1.0
 *
 * Creation date: Dec 11, 2006 
 * 
 * Tool for creating new UIMA Objects and other UIMA related things 
 **/

package de.julielab.jcore.utility;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collection;
import java.util.Iterator;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.impl.XmiCasDeserializer;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.jcas.cas.StringArray;
import org.apache.uima.jcas.tcas.Annotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import de.julielab.jcore.types.Header;

/**
 * Tool for creating new UIMA Objects and other UIMA related things
 * 
 * @author muehlhausen
 */
public class JCoReTools {

	/**
	 * Logger for this class
	 */
	private static final Logger log = LoggerFactory.getLogger(JCoReTools.class);

	/**
	 * Number of elements to be added if FSArray object is resized
	 */
	public static final int DEFAULT_ADDITION_SIZE = 10;

	/**
	 * Adds an Element of type FeatrueStructrue to a FSArray and resizes the
	 * FSArray if necessary
	 * 
	 * @param array
	 *            The array to what the feature structure should be added
	 * @param newElement
	 *            The feature structure that should be added to the array
	 * @return The array, that was added to
	 */
	public static FSArray addToFSArray(final FSArray array, final FeatureStructure newElement) {
		return addToFSArray(array, newElement, DEFAULT_ADDITION_SIZE);
	}

	/**
	 * Adds an Element of type FeatrueStructrue to a FSArray and resizes the
	 * FSArray if necessary, adding additionSize new elements to the array (only
	 * if necessary!)
	 * 
	 * @param array
	 *            The array to what the feature structure should be added
	 * @param newElement
	 *            The feature structure that should be added to the array
	 * @param additionSize
	 *            The size the array should be expanded
	 * @return The array, that was added to
	 */
	public static FSArray addToFSArray(final FSArray array, final FeatureStructure newElement, final int additionSize) {

		assert (additionSize > 0);

		FSArray outputArray = array;

		if (null == outputArray) {
			try {
				outputArray = new FSArray(newElement.getCAS().getJCas(), 1);
			} catch (CASException e1) {
				throw new JCoReUtilitiesException(e1);
			}
		}

		int lastElementIndex = outputArray.size() - 1;

		if (outputArray.get(lastElementIndex) != null) {
			log.trace("Last element of passed array was not null, thus array is full and a new one is created.");
			try {
				FSArray array2 = new FSArray(outputArray.getCAS().getJCas(), outputArray.size() + additionSize);
				array2.copyFromArray(outputArray.toArray(), 0, 0, outputArray.size());
				array2.set(lastElementIndex + 1, newElement);
				outputArray = array2;
				log.trace("New array is of size {}.", array2.size());
				return array2;
			} catch (CASException e) {
				e.printStackTrace();
			}
		} else {
			log.trace(
					"There is still room left over in the passed array, new element is appended after the last non-null element.");
		}

		while ((lastElementIndex > 0) && (outputArray.get(lastElementIndex - 1) == null)) {
			lastElementIndex--;
		}
		log.trace("Last non-null element was found on index {}, adding new element on position {}.",
				lastElementIndex - 1, lastElementIndex);
		outputArray.set(lastElementIndex, newElement);
		return outputArray;
	}

	/**
	 * Adds all elements in <tt>newElements</tt> to <tt>array</tt> resizes the
	 * FSArray if necessary.
	 * 
	 * @param inputArray
	 *            The array to what the feature structure should be added
	 * @param newElement
	 *            The feature structure that should be added to the array
	 * @return The array, that was added to
	 * @throws CASException
	 */
	public static FSArray addToFSArray(final FSArray inputArray,
			final Collection<? extends FeatureStructure> newElements) {

		FSArray array = inputArray;

		if (null == newElements || newElements.size() == 0)
			return array;

		if (null == array) {
			try {
				array = new FSArray(newElements.iterator().next().getCAS().getJCas(), 1);
			} catch (CASException e1) {
				throw new JCoReUtilitiesException(e1);
			}
		}

		try {
			int lastElementIndex = array.size() - 1;

			// Search for the last non-null element. If none is found,
			// lastElementIndex will actually be -1 after the
			// loop.
			while (lastElementIndex >= 0 && array.get(lastElementIndex) == null) {
				lastElementIndex--;
			}

			FSArray ret = null;
			// Is there enough space in the existing array to put all new
			// elements in it?
			int requiredSpace = lastElementIndex + 1 + newElements.size();
			if (requiredSpace <= array.size()) {
				log.trace(
						"Existing array has size {}. Since space for {} elements is required the passed array is kept.",
						array.size(), requiredSpace);
				ret = array;
			} else {
				log.trace("Passed array has size {} but there are {} elements overall, thus a new FSArray is created.",
						array.size(), requiredSpace);
				// There is not enough space for all new elements in the given
				// FSArray so create a new one
				ret = new FSArray(array.getCAS().getJCas(), requiredSpace);
				for (int i = 0; i <= lastElementIndex; i++)
					ret.set(i, array.get(i));
			}
			// Add the new elements.
			int currentIndex = lastElementIndex + 1;
			for (Iterator<? extends FeatureStructure> it = newElements.iterator(); it.hasNext(); currentIndex++) {
				ret.set(currentIndex, it.next());
			}
			return ret;
		} catch (CASException e) {
			throw new RuntimeException(e);
		}
	}

	public static FSArray copyFSArray(FSArray array) {
		FSArray output = null;
		try {
			output = new FSArray(array.getCAS().getJCas(), array.size());
			for (int i = 0; i < array.size(); ++i)
				output.set(i, array.get(i));
		} catch (CASException e) {
			throw new JCoReUtilitiesException(e);
		}
		return output;
	}

	public static StringArray addToStringArray(StringArray array, String element, JCas jcas) {
		try {
			StringArray newArray = null;
			if (array == null) {
				newArray = new StringArray(jcas, 1);
			} else {
				newArray = new StringArray(array.getCAS().getJCas(), array.size() + 1);
				newArray.copyFromArray(array.toArray(), 0, 0, array.size());
			}
			newArray.set(newArray.size() - 1, element);
			return newArray;
		} catch (CASException e) {
			e.printStackTrace();
		}
		return null;
	}

	public static StringArray addToStringArray(StringArray array, String[] elements, JCas jcas) {
		if (null == elements)
			return null;
		try {
			StringArray newArray = null;
			if (array == null) {
				newArray = new StringArray(jcas, elements.length);
				newArray.copyFromArray(elements, 0, 0, elements.length);
			} else {
				newArray = new StringArray(array.getCAS().getJCas(), array.size() + elements.length);
				newArray.copyFromArray(array.toArray(), 0, 0, array.size());
				newArray.copyFromArray(elements, 0, array.size(), elements.length);
			}
			return newArray;
		} catch (CASException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Prints the content of the FSArray to System.out
	 * 
	 * @param array
	 *            The array to be printed
	 */
	public static void printFSArray(FSArray array) {
		for (int i = 0; i < array.size(); i++) {
			FeatureStructure fs = array.get(i);
			System.out.println("fs[" + i + "] =  " + fs);
		}
	}

	/**
	 * <p>
	 * Returns the document ID of the document in the <code>JCas</code>.
	 * </p>
	 * <p>
	 * This can only be done when an annotation of type
	 * <code>de.julielab.jcore.types.Header</code> (or a subtype) is present and
	 * its feature <code>docId</code> is set.
	 * </p>
	 * 
	 * @param aJCas
	 * @return The value of of {@link de.julielab.jcore.types.Header#getDocId()}
	 */
	public static String getDocId(JCas aJCas) {
		AnnotationIndex<Annotation> headerIndex = aJCas.getAnnotationIndex(Header.type);
		FSIterator<Annotation> it = headerIndex.iterator();
		if (!it.hasNext())
			return null;
		Header header = (Header) it.next();
		String pubmedId = header.getDocId();
		return pubmedId;
	}

	/**
	 * <p>
	 * Deserializes an UTF-8 encoded XMI input stream into the given CAS.
	 * </p>
	 * <p>
	 * This method has largely been taken directly from
	 * {@link XmiCasDeserializer#deserialize(InputStream, CAS)}. However, the
	 * given input stream is explicitly transformed into an UTF-8 encoded
	 * {@link InputSource} for the XML parsing process. This is necessary
	 * because the Xerces internal UTF-8 handling is faulty with Unicode
	 * characters above the BMP (see
	 * https://issues.apache.org/jira/browse/XERCESJ-1257). Thus, this method
	 * explicitly uses UTF-8 encoding. For other encodings, use the default UIMA
	 * deserialization mechanism.
	 * </p>
	 * <p>
	 * The {@code attributeBufferSize} parameter only has an effect if the
	 * julielab Xerces version is on the classpath. Then, the XMLStringBuffer
	 * initial size is set via a system property. This can be very helpful for
	 * documents because UIMA stores the document text as an attribute to the
	 * sofa element in the XMI format. Such long attribute values are not
	 * expected by Xerces which initializes its attribute buffers with a size of
	 * 32 chars. Then, reading a large sofa (= document text) string results in
	 * a very long process of resizing the buffer array and copying the old
	 * buffer contents into the larger array. By setting a larger size from the
	 * beginning, a lot of time can be saved.
	 * </p>
	 * 
	 * @see https://issues.apache.org/jira/browse/XERCESJ-1257
	 * @param cas
	 *            The CAS to populate.
	 * @param is
	 *            The XMI data stream to populate the CAS with.
	 * @param attributeBufferSize
	 * @throws SAXException
	 * @throws IOException
	 */
	public static void deserializeXmi(CAS cas, InputStream is, int attributeBufferSize)
			throws SAXException, IOException {
		Reader reader = new InputStreamReader(is, "UTF-8");
		InputSource source = new InputSource(reader);
		source.setEncoding("UTF-8");

		if (attributeBufferSize > 0)
			System.setProperty("julielab.xerces.attributebuffersize", String.valueOf(attributeBufferSize));

		XMLReader xmlReader = XMLReaderFactory.createXMLReader();
		XmiCasDeserializer deser = new XmiCasDeserializer(cas.getTypeSystem());
		ContentHandler handler = deser.getXmiCasHandler(cas, false, null, -1);
		xmlReader.setContentHandler(handler);
		xmlReader.parse(source);

		System.clearProperty("julielab.xerces.attributebuffersize");
	}

}
