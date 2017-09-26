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
package de.julielab.jcore.ae.biosem;

import org.apache.uima.resource.SharedResourceObject;

import utils.DBUtils;

public interface DBUtilsProvider extends SharedResourceObject {
	DBUtils getTrainedDatabase();
	void closeDatabase();
}
