/* 
 Copyright (c) 2007 Arizona State University, Dept. of Computer Science and Dept. of Biomedical Informatics.
 This file is part of the BANNER Named Entity Recognition System, http://banner.sourceforge.net
 This software is provided under the terms of the Common Public License, version 1.0, as published by http://www.opensource.org.  For further information, see the file 'LICENSE.txt' included with this distribution.
 */

package banner.tagging.pipe;

import banner.types.Sentence;
import banner.types.Token;
import cc.mallet.pipe.Pipe;
import cc.mallet.types.Instance;
import cc.mallet.types.TokenSequence;

import java.util.List;

/**
 * This class is used by the CRFTagger as the base for the feature set.
 * 
 * @author Bob
 */
public class LChar extends Pipe
{
	private static final long serialVersionUID = 1L;

	private String prefix;

	public LChar(String prefix)
	{
		this.prefix = prefix;
	}

	@Override
	public Instance pipe(Instance carrier)
	{
		// TODO Configure folding characters into their class & test variants
		// TODO Intern
		Sentence sentence = (Sentence) carrier.getSource();
		List<Token> tokens = sentence.getTokens();

		TokenSequence ts = (TokenSequence) carrier.getData();
		for (int i = 0; i < ts.size(); i++)
		{
			Token bannerToken = tokens.get(i);
			cc.mallet.types.Token token = ts.get(i);

			// Add features to token
			String sentenceText = bannerToken.getSentence().getText();
			int start = bannerToken.getStart();
			String featureName = null;
			if (start > 0)
			{
				char ch = sentenceText.substring(start - 1, start).charAt(0);
				if (Character.isWhitespace(ch))
					featureName = prefix + "<WS>";
				else
					featureName = prefix + ch;
			}
			else
			{
				featureName = prefix + "<S>";
			}
			token.setFeatureValue(featureName, 1);
		}
		return carrier;
	}
}
