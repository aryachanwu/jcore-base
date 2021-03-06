package de.julielab.jcore.ae.flairner;

import de.julielab.jcore.ae.annotationadder.AnnotationAdderAnnotator;
import de.julielab.jcore.ae.annotationadder.AnnotationAdderConfiguration;
import de.julielab.jcore.ae.annotationadder.AnnotationAdderHelper;
import de.julielab.jcore.ae.annotationadder.AnnotationOffsetException;
import de.julielab.jcore.types.EmbeddingVector;
import de.julielab.jcore.types.EntityMention;
import de.julielab.jcore.types.Sentence;
import de.julielab.jcore.types.Token;
import de.julielab.jcore.utility.JCoReAnnotationTools;
import de.julielab.jcore.utility.JCoReTools;
import de.julielab.jcore.utility.index.Comparators;
import de.julielab.jcore.utility.index.JCoReTreeMapAnnotationIndex;
import de.julielab.jcore.utility.index.TermGenerators;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ResourceMetaData;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.DoubleArray;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ResourceMetaData(name = "JCoRe Flair Named Entity Recognizer", description = "This component starts a child process to a python interpreter and loads a Flair sequence tagging model. Sentences are taken from the CAS, sent to Flair for tagging and the results are written into the CAS. The annotation type to use can be configured. It must be a subtype of de.julielab.jcore.types.EntityMention. The tag of each entity is written to the specificType feature.")
@TypeCapability(inputs = {"de.julielab.jcore.types.Sentence", "de.julielab.jcore.types.Token"})
public class FlairNerAnnotator extends JCasAnnotator_ImplBase {

    public static final String PARAM_ANNOTATION_TYPE = "AnnotationType";
    public static final String PARAM_FLAIR_MODEL = "FlairModel";
    public static final String PARAM_PYTHON_EXECUTABLE = "PythonExecutable";
    public static final String PARAM_STORE_EMBEDDINGS = "StoreEmbeddings";
    public static final String PARAM_GPU_NUM = "GpuNumber";
    public static final String PARAM_COMPONENT_ID = "ComponentId";
    /**
     * The name of the Java system property to set the used GPU device externally.
     */
    public static final String GPU_NUM_SYS_PROP = "flairner.device";
    private final static Logger log = LoggerFactory.getLogger(FlairNerAnnotator.class);
    private PythonConnector connector;
    @ConfigurationParameter(name = PARAM_ANNOTATION_TYPE, description = "The UIMA type of which annotations should be created, e.g. de.julielab.jcore.types.EntityMention, of which the given type must be a subclass of. The tag of the entities is written to the specificType feature.")
    private String entityClass;
    @ConfigurationParameter(name = PARAM_FLAIR_MODEL, description = "Path to the Flair sequence tagger model.")
    private String flairModel;
    @ConfigurationParameter(name = PARAM_PYTHON_EXECUTABLE, mandatory = false, description = "The path to the python executable. Required is a python verion >=3.6. Defaults to 'python'.")
    private String pythonExecutable;
    @ConfigurationParameter(name = PARAM_STORE_EMBEDDINGS, mandatory = false, description = "Optional. Possible values: ALL, ENTITIES, NONE. The FLAIR SequenceTagger first computes the embeddings for each sentence and uses those as input for the actual NER algorithm. By default, the embeddings are not stored. By setting this parameter to ALL, the embeddings of all tokens of the sentence are retrieved from flair and stored in the embeddingVectors feature of each token. Setting the parameter to ENTITIES will restrict the embedding storage to those tokens which overlap with an entity recognized by FLAIR.")
    private StoreEmbeddings storeEmbeddings;
    @ConfigurationParameter(name = PARAM_GPU_NUM, mandatory = false, defaultValue="0", description = "Specifies the GPU device number to be used for FLAIR. This setting can be overwritten by the Java system property 'flairner.device'.")
    private int gpuNum;
    @ConfigurationParameter(name=PARAM_COMPONENT_ID, mandatory = false, description = "Specifies the componentId feature value given to the created annotations. Defaults to 'FlairNerAnnotator'.")
    private String componentId;
    private AnnotationAdderConfiguration adderConfig;

    /**
     * This method is called a single time by the framework at component
     * creation. Here, descriptor parameters are read and initial setup is done.
     */
    @Override
    public void initialize(final UimaContext aContext) throws ResourceInitializationException {
        entityClass = (String) aContext.getConfigParameterValue(PARAM_ANNOTATION_TYPE);
        flairModel = (String) aContext.getConfigParameterValue(PARAM_FLAIR_MODEL);
        storeEmbeddings = StoreEmbeddings.valueOf(Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_STORE_EMBEDDINGS)).orElse(StoreEmbeddings.NONE.name()));
        gpuNum = Optional.ofNullable((Integer)aContext.getConfigParameterValue(PARAM_GPU_NUM)).orElse(0);
        componentId = Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_COMPONENT_ID)).orElse(getClass().getSimpleName());
        if (System.getProperty(GPU_NUM_SYS_PROP) != null) {
            try {
                gpuNum = Integer.valueOf(System.getProperty(GPU_NUM_SYS_PROP));
                log.info("The GPU device number is set to '" + gpuNum + "' by the system property '" + GPU_NUM_SYS_PROP + "'. This causes the setting in the UIMA descriptor to be ignored.");
            } catch (NumberFormatException e) {
                log.error("The system property '" + GPU_NUM_SYS_PROP + "' is set to '" + System.getProperty(GPU_NUM_SYS_PROP) + "' which cannot be parsed to an integer. Please provide the device number of the GPU to use.", e);
            }
        }

        Optional<String> pythonExecutableOpt = Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_PYTHON_EXECUTABLE));
        if (!pythonExecutableOpt.isPresent()) {
            log.debug("No python executable given in the component descriptor, trying to read PYTHON environment variable.");
            final String pythonExecutableEnv = System.getenv("PYTHON");
            if (pythonExecutableEnv != null) {
                pythonExecutable = pythonExecutableEnv;
                log.info("Python executable: {} (from environment variable PYTHON).", pythonExecutable);
            }
        } else {
            pythonExecutable = pythonExecutableOpt.get();
            log.info("Python executable: {} (from descriptor)", pythonExecutable);
        }
        if (pythonExecutable == null) {
            pythonExecutable = "python";
            log.info("Python executable: {} (default)", pythonExecutable);
        }
        try {
            connector = new StdioPythonConnector(flairModel, pythonExecutable, storeEmbeddings, gpuNum);
            connector.start();
        } catch (IOException e) {
            log.error("Could not start the python connector", e);
            throw new ResourceInitializationException(e);
        }

        adderConfig = new AnnotationAdderConfiguration();
        adderConfig.setOffsetMode(AnnotationAdderAnnotator.OffsetMode.TOKEN);
        // FLAIR interprets all whitespaces as token boundaries
        adderConfig.setSplitTokensAtWhitespace(true);
        adderConfig.setDefaultUimaType(entityClass);

        log.info("{}: {}", PARAM_ANNOTATION_TYPE, entityClass);
        log.info("{}: {}", PARAM_FLAIR_MODEL, flairModel);
        log.info("{}: {}", PARAM_STORE_EMBEDDINGS, storeEmbeddings);
        log.info("{}: {}", PARAM_GPU_NUM, gpuNum);
    }

    /**
     * This method is called for each document going through the component. This
     * is where the actual work happens.
     */
    @Override
    public void process(final JCas aJCas) throws AnalysisEngineProcessException {
        int i = 0;
        final AnnotationIndex<Sentence> sentIndex = aJCas.getAnnotationIndex(Sentence.class);
        Map<String, Sentence> sentenceMap = new HashMap<>();
        for (Sentence sentence : sentIndex) {
            if (sentence.getId() == null)
                sentence.setId("s" + i++);
            sentenceMap.put(sentence.getId(), sentence);
        }
        try {
            final AnnotationAdderHelper helper = new AnnotationAdderHelper();
            final NerTaggingResponse taggingResponse = connector.tagSentences(StreamSupport.stream(sentIndex.spliterator(), false));
            final List<TaggedEntity> taggedEntities = taggingResponse.getTaggedEntities();
            for (TaggedEntity entity : taggedEntities) {
                final Sentence sentence = sentenceMap.get(entity.getDocumentId());
                EntityMention em = (EntityMention) JCoReAnnotationTools.getAnnotationByClassName(aJCas, entityClass);
                helper.setAnnotationOffsetsRelativeToSentence(sentence, em, entity, adderConfig);
                em.setSpecificType(entity.getTag());
                em.setConfidence(String.valueOf(entity.getLabelConfidence()));
                em.setComponentId(componentId);
                em.addToIndexes();
            }
            addTokenEmbeddings(aJCas, sentenceMap, helper, taggingResponse);
        } catch (IOException e) {
            log.error("Could not tag entities", e);
            throw new AnalysisEngineProcessException(e);
        } catch (InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
            log.error("Could not create an instance of the entity class {}", entityClass);
            throw new AnalysisEngineProcessException(e);
        } catch (CASException e) {
            log.error("Could not set the entity offsets", e);
            throw new AnalysisEngineProcessException(e);
        } catch (AnnotationOffsetException e) {
            final String docId = JCoReTools.getDocId(aJCas);
            log.error("Could not set the offsets of an annotation in document {}", docId);
            throw new AnalysisEngineProcessException(e);
        }
    }

    private void addTokenEmbeddings(JCas aJCas, Map<String, Sentence> sentenceMap, AnnotationAdderHelper helper, NerTaggingResponse taggingResponse) throws CASException {
        final List<TokenEmbedding> tokenEmbeddings = taggingResponse.getTokenEmbeddings();
        JCoReTreeMapAnnotationIndex<Long, Token> tokenIndex = null;
        if (!tokenEmbeddings.isEmpty())
            tokenIndex = new JCoReTreeMapAnnotationIndex<>(Comparators.longOverlapComparator(),TermGenerators.longOffsetTermGenerator(), TermGenerators.longOffsetTermGenerator(), aJCas, Token.type);
        Map<Token, List<double[]>> originalTokenEmbeddings = new HashMap<>();
        for (TokenEmbedding tokenEmbedding : tokenEmbeddings) {
            final Sentence sentence = sentenceMap.get(tokenEmbedding.getSentenceId());
            final List<Token> tokens = helper.createSentenceTokenMap(sentence, adderConfig).get(sentence);

            // The tokens created by the annotation adder helper may include subtokens of original tokens
            // that contain a whitespace. Thus, for one original token there might exist several subtokens.
            Token subtoken = tokens.get(tokenEmbedding.getTokenId() - 1);
            // There should always be exactly one overlapping token. We handle the case more general
            // but it shouldn't really be necessary.
            final List<Token> overlappingOriginalTokens = tokenIndex.searchFuzzy(subtoken).collect(Collectors.toList());
            for (Token originalToken : overlappingOriginalTokens) {
                final List<double[]> embeddingsOfToken = originalTokenEmbeddings.compute(originalToken, (t, l) -> {
                    if (l != null) return l;
                    return new ArrayList<>();
                });
                embeddingsOfToken.add(tokenEmbedding.getVector());
            }
        }
        // Average the embeddings where a token now has multiple embeddings due to the subtokenization
        // by FLAIR
        for (Token token : originalTokenEmbeddings.keySet()) {
            final List<double[]> subTokenEmbeddings = originalTokenEmbeddings.get(token);
            // The "avgEmbedding" is most of the time just the single, final embedding for the token
            double[] avgEmbedding = subTokenEmbeddings.get(0);
            for (int j = 1; j < subTokenEmbeddings.size(); j++) {
                for (int k = 0; k < avgEmbedding.length; k++) {
                    avgEmbedding[k] += subTokenEmbeddings.get(j)[k];
                }
            }
            if (subTokenEmbeddings.size() > 1) {
                for (int j = 0; j < avgEmbedding.length; j++) {
                    avgEmbedding[j] /= subTokenEmbeddings.size();
                }
            }
            final EmbeddingVector embeddingVector = new EmbeddingVector(aJCas, token.getBegin(), token.getEnd());
            final DoubleArray uimaVector = new DoubleArray(aJCas, avgEmbedding.length);
            uimaVector.copyFromArray(avgEmbedding, 0, 0, avgEmbedding.length);
            embeddingVector.setVector(uimaVector);
            embeddingVector.setSource(flairModel);
            embeddingVector.setComponentId(componentId);
            token.setEmbeddingVectors(JCoReTools.addToFSArray(token.getEmbeddingVectors(), embeddingVector));
        }
    }

    @Override
    public void collectionProcessComplete() throws AnalysisEngineProcessException {
        try {
            connector.shutdown();
        } catch (InterruptedException e) {
            log.error("Could not shutdown the python connector", e);
            throw new AnalysisEngineProcessException(e);
        }
    }


    public enum StoreEmbeddings {ALL, ENTITIES, NONE}
}
