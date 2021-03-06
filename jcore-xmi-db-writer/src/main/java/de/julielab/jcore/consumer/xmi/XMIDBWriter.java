/**
 * /**
 * XMIDBWriter.java
 * <p>
 * Copyright (c) 2012, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: faessler
 * <p>
 * Current version: 1.0
 * Since version:   1.0
 * <p>
 * Creation date: 11.12.2012
 */

/**
 *
 */
package de.julielab.jcore.consumer.xmi;

import de.julielab.costosys.cli.TableNotFoundException;
import de.julielab.costosys.configuration.FieldConfig;
import de.julielab.costosys.dbconnection.CoStoSysConnection;
import de.julielab.costosys.dbconnection.DataBaseConnector;
import de.julielab.costosys.dbconnection.util.TableSchemaMismatchException;
import de.julielab.jcore.ae.checkpoint.DocumentId;
import de.julielab.jcore.ae.checkpoint.DocumentReleaseCheckpoint;
import de.julielab.jcore.types.Header;
import de.julielab.jcore.types.XmiMetaData;
import de.julielab.jcore.types.ext.DBProcessingMetaData;
import de.julielab.xml.*;
import de.julielab.xml.binary.BinaryJeDISNodeEncoder;
import de.julielab.xml.binary.BinaryStorageAnalysisResult;
import de.julielab.xml.util.MissingBinaryMappingException;
import de.julielab.xml.util.XMISplitterException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.TypeSystem;
import org.apache.uima.cas.impl.XmiCasSerializer;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.ducc.Workitem;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ResourceMetaData;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * @author faessler
 */
@ResourceMetaData(name = "JCoRe XMI Database Writer", vendor = "JULIE Lab Jena, Germany", description = "This component " +
        "is capable of storing the standard UIMA serialization of documents in one or even multiple database tables. " +
        "The UIMA serialization format is XMI, an XML format that expressed an annotation graph. This component " +
        "either stores the whole annotation graph in XMI format in a database row, together with the document ID. " +
        "Alternatively, it makes use of the jcore-xmi-splitter to segment the annotation graph with respect to a " +
        "user specified list of annotation types. Then, the XMI data of each annotation type is extracted from the " +
        "document XMI data and stored in a separate table. The tables are created automatically according to the " +
        "primary key of the active table schema in the Corpus Storage System (CoStoSys) configuration file that is " +
        "also given as a parameter. The jcore-xmi-db-reader is capable of reading this kind of distributed annotation " +
        "graph and reassemble a valid XMI document which then cas be deserialized into a CAS. This consumer is " +
        "UIMA DUCC compatible. It requires the collection reader to forward the work item CAS to the consumer. This is " +
        "required so the consumer knows that a work item has been finished and that all cached data - in this case the " +
        "XMI data - should be flushed. This is important! Without the forwarding of the work item CAS, the last batch " +
        "of cached XMI data will not be written into the database. This component is part " +
        "of the Jena Document Information System, JeDIS.")
public class XMIDBWriter extends JCasAnnotator_ImplBase {
    public static final String PARAM_COSTOSYS_CONFIG = "CostosysConfigFile";
    public static final String PARAM_UPDATE_MODE = "UpdateMode";
    public static final String PARAM_DO_GZIP = "PerformGZIP";
    public static final String PARAM_USE_BINARY_FORMAT = "UseBinaryFormat";
    public static final String PARAM_STORE_ALL = "StoreEntireXmiData";

    public static final String PARAM_TABLE_DOCUMENT = "DocumentTable";
    public static final String PARAM_ANNOS_TO_STORE = "AnnotationsToStore";
    public static final String PARAM_STORE_RECURSIVELY = "StoreRecursively";

    public static final String PARAM_BASE_DOCUMENT_ANNOTATION_TYPES = "BaseDocumentAnnotationTypes";

    public static final String PARAM_DELETE_OBSOLETE_ANNOTATIONS = "DeleteObsoleteAnnotations";
    public static final String PARAM_ATTRIBUTE_SIZE = "IncreasedAttributeSize";
    public static final String PARAM_ANNO_DEFAULT_QUALIFIER = "DefaultAnnotationColumnQualifier";
    public static final String PARAM_COMPONENT_DB_NAME = "ComponentDbName";
    public static final String PARAM_STORE_BASE_DOCUMENT = "StoreBaseDocument";
    public static final String PARAM_WRITE_BATCH_SIZE = "WriteBatchSize";
    public static final String PARAM_XMI_META_SCHEMA = "XmiMetaTablesSchema";
    public static final String PARAM_FEATURES_TO_MAP_DRYRUN = "BinaryFeaturesToMapDryRun";
    public static final String PARAM_BINARY_FEATURES_BLACKLIST = "BinaryFeaturesBlacklist";
    public static final String PARAM_ADD_SHA_HASH = "AddShaHash";
    private static final Logger log = LoggerFactory.getLogger(XMIDBWriter.class);
    // The mappings are keyed by the costosys.xml path and the table schema, see 'mappingCacheKey'.
    // The idea is to save costly database connections by sharing updating mapping across threads.
    private static Map<String, Map<String, Integer>> binaryStringMapping = Collections.emptyMap();
    private static Map<String, Map<String, Boolean>> binaryMappedFeatures = Collections.emptyMap();
    private static Map<String, Map<DocumentId, XmiBufferItem>> splitterResultMap;
    private static Map<String, Map<String, Pair<List<XmiBufferItem>, CountDownLatch>>> xmiBufferItemsToProcess;
    private static ReentrantLock missingMappingsGatheringLock;
    private static CountDownLatch missingMappingsGatheringLatch = new CountDownLatch(0);
    private static ReentrantLock mappingUpdateLock;
    private DataBaseConnector dbc;
    @ConfigurationParameter(name = PARAM_UPDATE_MODE, description = "If set to false, the attempt to write new data " +
            "into an XMI document or annotation table that already has data for the respective document, will result " +
            "in an error. If set to true, there will first occur a check if there already is XMI data for the " +
            "currently written document and, if so, the contents will be updated. It is important to keep in " +
            "mind that the update also includes empty data. That is, if an annotation type is specified in " +
            "'" + PARAM_ANNOS_TO_STORE + "' for which the current does not have data, possibly existing data will just be " +
            "deleted.")
    private Boolean updateMode;
    @ConfigurationParameter(name = PARAM_DELETE_OBSOLETE_ANNOTATIONS, mandatory = false, defaultValue = "false",
            description = "Only in effect if '" + PARAM_STORE_BASE_DOCUMENT + "' is set to 'true'. Then, " +
                    "already existing annotation tables are retrieved from an internal database table that is " +
                    "specifically maintained to list existing annotation tables. When storing the base document, the " +
                    "annotations in these tables are removed for the document if this parameter is set to 'true', " +
                    "except tables specified in '" + PARAM_ANNOS_TO_STORE + "'. " +
                    "The idea is that " +
                    "when storing the base document, all existing annotations become obsolete since they refer " +
                    "to a base document that no longer exists.")
    private Boolean deleteObsolete;
    @ConfigurationParameter(name = PARAM_DO_GZIP, description = "Determines if the XMI data should be stored " +
            "compressed or uncompressed. Without compression, the data will be directly viewable in a database " +
            "browser, whereas compressed data appears as opaque byte sequence. Compression is supposed to " +
            "reduce traffic over the network and save storage space on the database server.")
    private Boolean doGzip;
    @ConfigurationParameter(name = PARAM_ATTRIBUTE_SIZE, mandatory = false, description = "Integer that defines the maximum attribute size for " +
            "the XMIs. Standard (parser wise) is 65536 * 8. It may be necessary to rise this value for larger documents " +
            "since the document text is stored as an attribute of an XMI element.")
    private Integer attributeSize;
    @ConfigurationParameter(name = PARAM_STORE_ALL, description = "Boolean parameter indicating if the whole document " +
            "should be stored as one large XMI data block. " +
            "In this case there must not be any annotations specified for selection and the '" + PARAM_STORE_BASE_DOCUMENT + "' " +
            "parameter will have no effect.")
    private Boolean storeAll;
    @ConfigurationParameter(name = PARAM_TABLE_DOCUMENT, description = "String parameter indicating the name of the " +
            "table where the XMI data will be stored (if StoreEntireXmiData is true) or " +
            "where the base document is (to be) stored (if the base document or annotation data is written). If the name is schema qualified, " +
            "i.e. contains a dot, the table name will be used as provided. If no schema is qualified, the active " +
            "data postgres schema as configured in the CoStoSys configuration will be used to find or create the " +
            "table.")
    private String docTableParamValue;
    private List<String> unqualifiedAnnotationNames;
    @ConfigurationParameter(name = PARAM_STORE_RECURSIVELY, description = "Only in effect when storing annotations " +
            "separately from the base document. If set to true, annotations that are referenced by other annotations, " +
            "i.e. are (direct or indirect) features of other annotations, they " +
            "will be stored in the same table as the referencing annotation. For example, POS tags may be store " +
            "together with tokens this way. If, however, a referenced annotation type is itself to be stored, " +
            "it will be segmented away and stored in its own table.")
    private Boolean recursively;
    @ConfigurationParameter(name = PARAM_STORE_BASE_DOCUMENT, description = "Boolean parameter indicating if the base " +
            "document should be stored as well when annotations are specified for selection. The base document is " +
            "the part of the XMI file that includes the document text. If you want to store annotations right with " +
            "the base document, specify those in the '" + PARAM_BASE_DOCUMENT_ANNOTATION_TYPES + "' parameter.")
    private Boolean storeBaseDocument;
    @ConfigurationParameter(name = PARAM_BASE_DOCUMENT_ANNOTATION_TYPES, mandatory = false, description = "Array " +
            "parameter that takes Java annotation type names. These names will be stored with the base document, " +
            "if the 'StoreBaseDocument' parameter is set to true.")
    private Set<String> baseDocumentAnnotationTypes;
    @ConfigurationParameter(name = PARAM_ANNO_DEFAULT_QUALIFIER, mandatory = false, description =
            "This optional parameter specifies the qualifier given to annotation storage columns in the XMI by default. If " +
                    "omitted, no qualifier will added. The column names derived from " +
                    "the annotation types specified with the '" + PARAM_ANNOS_TO_STORE + "' " +
                    "parameter will be prefixed with this qualifier, separated by the dollar character. The default can be overwritten for individual " +
                    "types. See the description of the '" + PARAM_ANNOS_TO_STORE + "' parameter.")
    private String defaultAnnotationColumnQualifier;
    @ConfigurationParameter(name = PARAM_WRITE_BATCH_SIZE, mandatory = false, defaultValue = "50", description =
            "The number of processed CASes after which the XMI data should be flushed into the database. Defaults to 50.")
    private int writeBatchSize;
    @ConfigurationParameter(name = PARAM_XMI_META_SCHEMA, mandatory = false, defaultValue = "public", description = "Each XMI file defines a number of XML namespaces according to the types used in the document. Those namespaces are stored in a table named '" + MetaTableManager.XMI_NS_TABLE + "' when splitting annotations in annotation modules for later retrieval by the XMI DB reader. This parameter allows to specify in which Postgres schema this table should be stored. Also, the table listing the annotation tables is stored in this Postgres schema. Defaults to 'public'.")
    private String xmiMetaSchema;
    @ConfigurationParameter(name = PARAM_USE_BINARY_FORMAT, mandatory = false, defaultValue = "false", description = "If set to true, the XMI data is stored in a binary format to avoid a lot of the XML format overhead. This is meant to reduce storage size.")
    private boolean useBinaryFormat;
    private XmiSplitter splitter;
    private BinaryJeDISNodeEncoder binaryEncoder;
    private List<XmiBufferItem> xmiItemBuffer = new ArrayList<>();
    private List<XmiData> annotationModules = new ArrayList<>();
    private String schemaDocument;
    private String effectiveDocTableName;
    private MetaTableManager metaTableManager;
    private AnnotationTableManager annotationTableManager;
    private int headerlessDocuments = 0;
    private int currentBatchSize = 0;
    private XmiDataInserter annotationInserter;
    @ConfigurationParameter(name = PARAM_COMPONENT_DB_NAME, description = " Subset tables store the name of the last " +
            "component that has sent data for a document. This parameter allows to specify a custom name for each CAS " +
            "DB Consumer. Defaults to the implementation class name.", defaultValue = "XMIDBWriter")
    private String componentDbName;
    private String subsetTable;
    @ConfigurationParameter(name = PARAM_COSTOSYS_CONFIG, description = "File path or classpath resource location " +
            "of a Corpus Storage System (CoStoSys) configuration file. This file specifies the database to " +
            "write the XMI data into and the data table schema. This schema must at least define the primary key " +
            "columns that the storage tables should have for each document. The primary key is currently just " +
            "the document ID. Thus, at the moment, primary keys can only consist of a single element when using " +
            "this component. This is a shortcoming of this specific component and must be changed here, if necessary.")
    private String dbcConfigPath;
    @ConfigurationParameter(name = PARAM_ANNOS_TO_STORE, mandatory = false, description = "An array of qualified " +
            "UIMA type names, for instance de.julielab.jcore.types.Sentence. Annotations of those types are segmented " +
            "away from the serialized document annotation " +
            "graph in XMI format for storage in separate tables. When the '" + PARAM_STORE_RECURSIVELY + "' parameter is set to true, " +
            "annotations are stored together with referenced annotations, if those are not specified in the list " +
            "of additional tables themselves. The table names are directly derived from the " +
            "annotation type names by converting dots to underlines and adding a postgres schema qualification " +
            "according to the active data postgres schema defined in the CoStoSys configuration. If an annotation " +
            "table should be stored or looked up in another postgres schema, prepend the type name with the " +
            "string '<schema>:', e.g. 'myschema:de.julielab.jcore.types.Token.")
    private String[] annotations;
    @ConfigurationParameter(name = PARAM_FEATURES_TO_MAP_DRYRUN, mandatory = false, defaultValue = "false",
            description = "This parameter is useful when using the binary format and has no effect if not. Then, the UIMA type features " +
                    "that should be mapped to integers will be determined automatically from the input. For each " +
                    "document that has a string (!) feature not seen before, the ratio of occurrences of that feature in the " +
                    "document to the number of distinct values of the feature in the document determines whether " +
                    "or not the feature values will be mapped. This purely one-instance statistical approach " +
                    "can have unwanted results in that a feature is mapped or not mapped that should not be or should. " +
                    "Setting this parameter to true will cause the algorithm that determines which features to map to output details about which features " +
                    "would be mapped without actually writing anything into the database. This is done on the INFO log level. This can be used for " +
                    "new corpora in order to check which features should manually be switched on or off for mapping.")
    private boolean featuresToMapDryRun;
    @ConfigurationParameter(name = PARAM_BINARY_FEATURES_BLACKLIST, mandatory = false, description = "A blacklist of " +
            "full UIMA feature names. The listed features will be excluded from binary value mapping. This makes sense " +
            "for features with a lot of different values that still come up as being mapping from the automatic features-to-map selection algorithm." +
            "It also makes sense for features that only consist of strings of length around 4 characters length or shorter. " +
            "Then, the replacement with an integer of 4 bytes won't probably make much sense (unless the strings mainly " +
            "consist of characters that require more than 1 byte, of course).")
    private String[] binaryFeaturesBlacklistParameter;
    @ConfigurationParameter(name = PARAM_ADD_SHA_HASH, mandatory = false, description = "Possible values: document_text. If this parameter is set to a valid value, the SHA256 hash for the given value will be calculated, base64 encoded and added to each document as a new column in the document table. The column will be named after the parameter value, suffixed by '_sha256'.")
    private String documentItemToHash;
    private Map<DocumentId, String> shaMap;
    private String mappingCacheKey;
    private DocumentReleaseCheckpoint docReleaseCheckpoint;
    private List<DocumentId> currentDocumentIdBatch;
    @ConfigurationParameter(name = DocumentReleaseCheckpoint.PARAM_JEDIS_SYNCHRONIZATION_KEY, mandatory = false, description = DocumentReleaseCheckpoint.SYNC_PARAM_DESC)
    private String jedisSyncKey;

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.uima.analysis_component.AnalysisComponent_ImplBase#initialize
     * (org.apache.uima.UimaContext)
     */
    @Override
    public void initialize(UimaContext aContext) throws ResourceInitializationException {
        super.initialize(aContext);
        checkParameters(aContext);

        dbcConfigPath = (String) aContext.getConfigParameterValue(PARAM_COSTOSYS_CONFIG);
        try {
            dbc = new DataBaseConnector(dbcConfigPath);
        } catch (FileNotFoundException e1) {
            throw new ResourceInitializationException(e1);
        }
        updateMode = aContext.getConfigParameterValue(PARAM_UPDATE_MODE) == null ? false
                : (Boolean) aContext.getConfigParameterValue(PARAM_UPDATE_MODE);
        doGzip = aContext.getConfigParameterValue(PARAM_DO_GZIP) == null ? false
                : (Boolean) aContext.getConfigParameterValue(PARAM_DO_GZIP);
        storeAll = aContext.getConfigParameterValue(PARAM_STORE_ALL) == null ? false
                : (Boolean) aContext.getConfigParameterValue(PARAM_STORE_ALL);
        docTableParamValue = (String) aContext.getConfigParameterValue(PARAM_TABLE_DOCUMENT);
        documentItemToHash = (String) aContext.getConfigParameterValue(PARAM_ADD_SHA_HASH);
        storeBaseDocument = aContext.getConfigParameterValue(PARAM_STORE_BASE_DOCUMENT) == null ? false
                : (Boolean) aContext.getConfigParameterValue(PARAM_STORE_BASE_DOCUMENT);
        deleteObsolete = Optional.ofNullable((Boolean) aContext.getConfigParameterValue(PARAM_DELETE_OBSOLETE_ANNOTATIONS)).orElse(false);
        // The deletion of obsolete annotations should only be active when the base document is stored because then, old annotations won't be valid any more.
        deleteObsolete &= storeBaseDocument;
        baseDocumentAnnotationTypes = Arrays.stream(
                Optional.ofNullable((String[]) aContext.getConfigParameterValue(PARAM_BASE_DOCUMENT_ANNOTATION_TYPES))
                        .orElse(new String[0]))
                .collect(Collectors.toSet());
        attributeSize = (Integer) aContext.getConfigParameterValue(PARAM_ATTRIBUTE_SIZE);
        writeBatchSize = Optional.ofNullable((Integer) aContext.getConfigParameterValue(PARAM_WRITE_BATCH_SIZE)).orElse(50);
        componentDbName = Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_COMPONENT_DB_NAME)).orElse(getClass().getSimpleName());
        defaultAnnotationColumnQualifier = (String) aContext.getConfigParameterValue(PARAM_ANNO_DEFAULT_QUALIFIER);
        annotations = (String[]) Optional.ofNullable(aContext.getConfigParameterValue(PARAM_ANNOS_TO_STORE)).orElse(new String[0]);
        Set<String> annotationsSet = Arrays.stream(annotations).collect(Collectors.toCollection(LinkedHashSet::new));
        if (annotationsSet.size() != annotations.length)
            log.warn("Some annotation module names for storage were duplicated. They are de-duplicated to avoid errors when adding the annotation modules into the database.");
        xmiMetaSchema = Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_XMI_META_SCHEMA)).orElse("public");
        useBinaryFormat = Optional.ofNullable((Boolean) aContext.getConfigParameterValue(PARAM_USE_BINARY_FORMAT)).orElse(false);
        featuresToMapDryRun = Optional.ofNullable((Boolean) aContext.getConfigParameterValue(PARAM_FEATURES_TO_MAP_DRYRUN)).orElse(false);
        binaryFeaturesBlacklistParameter = (String[]) aContext.getConfigParameterValue(PARAM_BINARY_FEATURES_BLACKLIST);
        if (useBinaryFormat) {
            this.mappingCacheKey = dbcConfigPath + "_" + xmiMetaSchema;
            binaryMappedFeatures = new ConcurrentHashMap<>();
            binaryMappedFeatures.put(mappingCacheKey, new ConcurrentHashMap<>());
            binaryStringMapping = new ConcurrentHashMap<>();
            binaryStringMapping.put(mappingCacheKey, new ConcurrentHashMap<>());
            splitterResultMap = new ConcurrentHashMap<>();
            splitterResultMap.put(mappingCacheKey, new ConcurrentHashMap<>());
            xmiBufferItemsToProcess = new ConcurrentHashMap<>();
            xmiBufferItemsToProcess.put(mappingCacheKey, new ConcurrentHashMap<>(writeBatchSize));
            mappingUpdateLock = new ReentrantLock();
        }
        if (useBinaryFormat && binaryFeaturesBlacklistParameter != null) {
            binaryMappedFeatures.put(mappingCacheKey, Arrays.stream(binaryFeaturesBlacklistParameter).collect(Collectors.toMap(Function.identity(), x -> false, (x, y) -> x && y, ConcurrentHashMap::new)));
        }

        if (xmiMetaSchema.isBlank())
            throw new ResourceInitializationException(new IllegalArgumentException("The XMI meta table Postgres schema must either be omitted at all or non-empty but was."));

        unqualifiedAnnotationNames = Collections.emptyList();

        dbc.reserveConnection();


        String hashColumnName = null;
        if (storeAll) {
            schemaDocument = dbc.addXmiDocumentFieldConfiguration(dbc.getActiveTableFieldConfiguration().getPrimaryKeyFields().collect(Collectors.toList()), doGzip || useBinaryFormat).getName();
            dbc.setActiveTableSchema(schemaDocument);
        } else {
            List<Map<String, String>> xmiAnnotationColumnsDefinitions = new ArrayList<>();
            for (String qualifiedAnnotation : annotations) {
                final String columnName = AnnotationTableManager.convertQualifiedAnnotationTypeToColumnName(qualifiedAnnotation, defaultAnnotationColumnQualifier);
                final Map<String, String> field = FieldConfig.createField(
                        JulieXMLConstants.NAME, columnName,
                        JulieXMLConstants.GZIP, String.valueOf(doGzip),
                        JulieXMLConstants.RETRIEVE, "true",
                        JulieXMLConstants.TYPE, doGzip || useBinaryFormat ? "bytea" : "xml"
                );
                xmiAnnotationColumnsDefinitions.add(field);
            }
            if (documentItemToHash != null) {
                shaMap = new HashMap<>();
                hashColumnName = documentItemToHash + "_sha256";
                xmiAnnotationColumnsDefinitions.add(FieldConfig.createField(JulieXMLConstants.NAME, hashColumnName, JulieXMLConstants.TYPE, "text"));
                if (dbc.tableExists(docTableParamValue))
                    dbc.assureColumnsExist(docTableParamValue, Collections.singletonList(hashColumnName), "text");
            }
            final FieldConfig fieldConfig = dbc.addXmiTextFieldConfiguration(dbc.getActiveTableFieldConfiguration().getPrimaryKeyFields().collect(Collectors.toList()), xmiAnnotationColumnsDefinitions, doGzip || useBinaryFormat);
            schemaDocument = fieldConfig.getName();
            if (null != annotations) {
                // In 'unqualifiedAnnotationNames' we keep the un-qualified annotation types. We need those for the XMI splitter.
                // We also create a map from type name to schema so we know where to put each annotation later
                unqualifiedAnnotationNames = new ArrayList<>(annotations.length);
                for (int i = 0; i < annotations.length; i++) {
                    final int colonIndex = annotations[i].indexOf(':');
                    if (colonIndex < 0) {
                        unqualifiedAnnotationNames.add(annotations[i]);
                    } else {
                        String typeName = annotations[i].substring(colonIndex + 1);
                        unqualifiedAnnotationNames.add(typeName);
                    }
                }
            } else {
                unqualifiedAnnotationNames = Collections.emptyList();
            }
            recursively = aContext.getConfigParameterValue(PARAM_STORE_RECURSIVELY) == null ? false
                    : (Boolean) aContext.getConfigParameterValue(PARAM_STORE_RECURSIVELY);

        }
        try {
            // Here we need to pass the original 'annotations' parameter because it contains the schema qualification for the annotation tables
            annotationTableManager = new AnnotationTableManager(dbc, docTableParamValue, Arrays.asList(annotations), doGzip || useBinaryFormat, schemaDocument,
                    storeAll, storeBaseDocument, defaultAnnotationColumnQualifier, xmiMetaSchema);
        } catch (TableSchemaMismatchException e) {
            throw new ResourceInitializationException(e);
        }

        // Important: The document table must come first because the
        // annotation tables have foreign keys to the document table.
        // Thus, we can't add annotations for documents not in the
        // document table.
        Set<String> annotationModulesColumnNames = new HashSet<>();
        effectiveDocTableName = annotationTableManager.getEffectiveDocumentTableName(docTableParamValue);
        if (!storeAll) {
            // Here we use the schema-qualified 'annotations' field
            for (String annotation : annotations) {
                String annotationModuleColumName = annotationTableManager.convertUnqualifiedAnnotationTypetoColumnName(annotation, storeAll);
                annotationModulesColumnNames.add(annotationModuleColumName);
            }
        }
        // does currently only compare the primary keys...
        if (dbc.tableExists(effectiveDocTableName))
            checkTableDefinition(effectiveDocTableName, schemaDocument);

        if (updateMode) {
            List<String> obsoleteAnnotationTableNames = annotationTableManager.getObsoleteAnnotationTableNames();
            if (!obsoleteAnnotationTableNames.isEmpty()) {
                log.info(
                        "Annotations from the following tables will be obsolete by updating the base document and will be deleted: {}"
                        , obsoleteAnnotationTableNames);
            }
        }
        if (storeAll) {
            if (null != attributeSize) {
                splitter = new WholeXmiStaxSplitter(docTableParamValue, attributeSize);
            } else {
                splitter = new WholeXmiStaxSplitter(docTableParamValue);
            }
        } else {
            if (null != attributeSize) {
                splitter = new StaxXmiSplitter(new HashSet<>(unqualifiedAnnotationNames), recursively, storeBaseDocument,
                        baseDocumentAnnotationTypes, attributeSize);
            } else {
                splitter = new StaxXmiSplitter(new HashSet<>(unqualifiedAnnotationNames), recursively, storeBaseDocument,
                        baseDocumentAnnotationTypes);
            }
        }
        if (useBinaryFormat) {
            this.binaryEncoder = new BinaryJeDISNodeEncoder();
        }

        log.info(XMIDBWriter.class.getName() + " initialized.");
        log.info("Effective document table name: {}", effectiveDocTableName);
        log.info("Is base document stored: {}", storeBaseDocument);
        log.info("CAS XMI data will be GZIPed: {}", doGzip);
        log.info("Use binary format: {}", useBinaryFormat);
        log.info("Is the whole, unsplit XMI document stored: {}", storeAll);
        log.info("Annotations belonging to the base document: {}", baseDocumentAnnotationTypes);
        log.info("Annotation types to store in the columns of {}: {}", effectiveDocTableName, unqualifiedAnnotationNames);
        log.info("Store annotations recursively: {}", recursively);
        log.info("Update mode: {}", updateMode);
        log.info("Base document table schema: {}", schemaDocument);
        log.info("Batch size of cached documents sent to database: {}", writeBatchSize);
        log.info("Do a dry run and output binary features to map: {}", featuresToMapDryRun);


        metaTableManager = new MetaTableManager(dbc, xmiMetaSchema);
        annotationInserter = new XmiDataInserter(annotationModulesColumnNames, dbc,
                schemaDocument, storeAll, updateMode, componentDbName, hashColumnName);
        dbc.releaseConnections();

        jedisSyncKey = (String) aContext.getConfigParameterValue(DocumentReleaseCheckpoint.PARAM_JEDIS_SYNCHRONIZATION_KEY);
        if (jedisSyncKey != null) {
            docReleaseCheckpoint = DocumentReleaseCheckpoint.get();
            docReleaseCheckpoint.register(jedisSyncKey);
        }
        currentDocumentIdBatch = new ArrayList<>();
    }

    private void checkTableDefinition(String annotationTableName, String schemaAnnotation) throws ResourceInitializationException {
        try {
            dbc.checkTableHasSchemaColumns(annotationTableName, schemaAnnotation);
        } catch (TableSchemaMismatchException | TableNotFoundException e) {
            throw new ResourceInitializationException(e);
        }
    }

    private void checkParameters(UimaContext aContext) throws ResourceInitializationException {
        if (aContext.getConfigParameterValue(PARAM_COSTOSYS_CONFIG) == null)
            throw new ResourceInitializationException(new IllegalStateException(
                    "The database configuration file is null. You must provide the path to a valid configuration file."));
        if (aContext.getConfigParameterValue(PARAM_TABLE_DOCUMENT) == null)
            throw new ResourceInitializationException(new IllegalStateException(
                    "The document table is null. You must provide it to either store the entire xmi data, to store the base document "
                            + " or to update the next possible xmi id."));
        String[] annotations = (String[]) aContext.getConfigParameterValue(PARAM_ANNOS_TO_STORE);
        if (aContext.getConfigParameterValue(PARAM_STORE_ALL) == null && annotations == null
                && aContext.getConfigParameterValue(PARAM_STORE_BASE_DOCUMENT) == null) {
            throw new ResourceInitializationException(new IllegalStateException(
                    "The parameter to store the entire xmi data is not checked, but there are no annotations specified to"
                            + " store instead. You must provide the names of the selected annotations, if you do not want to "
                            + " write the entire CAS data."));
        } else if (aContext.getConfigParameterValue(PARAM_STORE_ALL) != null
                && (Boolean) aContext.getConfigParameterValue(PARAM_STORE_ALL) && annotations != null
                && annotations.length > 0) {
            throw new ResourceInitializationException(new IllegalStateException(
                    "The parameter to store the entire xmi data is checked and there are annotations specified to store."
                            + " You can only either write the entire CAS data or select annotations, but not both."));
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.uima.analysis_component.JCasAnnotator_ImplBase#process(org
     * .apache.uima.jcas.JCas)
     */
    @Override
    public void process(JCas aJCas) throws AnalysisEngineProcessException {
        try {
            // UIMA DUCC compatibility: Check if this is the work item CAS.
            // If so, this is the signal that the current work item has finished processing.
            // Send the currently cached data to the database and return.
            try {
                Workitem workitem = JCasUtil.selectSingle(aJCas, Workitem.class);
                log.trace("Work item feature structure found in the current CAS. Sending data to the database and " +
                        "returning.");
                if (workitem.getLastBlock())
                    collectionProcessComplete();
                else
                    batchProcessComplete();
                return;
            } catch (IllegalArgumentException e) {
                // Do nothing; this is not the work item CAS
            }
            DocumentId docId = getDocumentId(aJCas);
            if (docId == null) {
                log.warn("The current document does not have a document ID. It is omitted from database import.");
                return;
            }

            currentDocumentIdBatch.add(docId);

            if (subsetTable == null) {
                Collection<DBProcessingMetaData> metaData = JCasUtil.select(aJCas, DBProcessingMetaData.class);
                if (!metaData.isEmpty()) {
                    if (metaData.size() > 1)
                        throw new AnalysisEngineProcessException(new IllegalArgumentException(
                                "There is more than one type of DBProcessingMetaData in document " + docId));
                    subsetTable = metaData.stream().findAny().get().getSubsetTable();

                    if (subsetTable != null && storeBaseDocument) {
                        // Check if we are about to read from a mirror subset and to update the base document. This is not allowed
                        // because it would cause the subset to reset the updated columns, causing the same documents to be
                        // read again and again.
                        try (CoStoSysConnection costoConn = dbc.obtainOrReserveConnection()) {
                            Map<String, Boolean> mirrorSubsetNames = dbc.getMirrorSubsetNames(costoConn, effectiveDocTableName);
                            if (mirrorSubsetNames.keySet().contains(subsetTable.replace("^[^.]\\.", "")))
                                throw new AnalysisEngineProcessException(new IllegalArgumentException("The read subset table " + subsetTable + " is a mirror subset its document table " + effectiveDocTableName + " and the base document should be stored. This base document storage would cause all its subset to reset the updated documents. Thus, the subset " + subsetTable + " would be partially reset while processing, reading the same documents over and over again. This is therefore illegal."));
                        }
                    }
                }
            }
            try {
                serializeCasIntoBuffer(aJCas, docId);
            } catch (SAXParseException e) {
                log.error("Could not serialize CAS for document {}", Arrays.toString(docId.getId()), e);
                return;
            }

            handleAddhash(aJCas, docId);

            ++currentBatchSize;
            if (currentBatchSize % writeBatchSize == 0) {
                log.trace("Document nr {} processed, filling batch nr {} of size {}, sending to database.", currentBatchSize, currentBatchSize / writeBatchSize, writeBatchSize);
                batchProcessComplete();
            }
        } catch (Throwable throwable) {
            String docid = "<unknown>";
            try {
                docid = JCasUtil.selectSingle(aJCas, Header.class).getDocId();
            } catch (Exception e) {
                // nothing, use default
            }
            log.error("Error occurred at document {}: ", docid, throwable);
            throw throwable;
        }
    }

    private void handleAddhash(JCas aJCas, DocumentId docId) {
        if (documentItemToHash != null) {
            final String documentText = aJCas.getDocumentText();
            final byte[] sha = DigestUtils.sha256(documentText.getBytes());
            final String finalHash = Base64.encodeBase64String(sha);
            shaMap.put(docId, finalHash);
        }
    }

    /**
     * <p>This method gets all the current items from xmiItemBuffer, creates annotation modules and writes those into
     * the annotationModules field.</p>
     *
     * @throws AnalysisEngineProcessException
     */
    private boolean processXmiBuffer() throws AnalysisEngineProcessException {
        if (xmiItemBuffer.isEmpty()) {
            log.debug("The XMI item buffer is empty, nothing to do.");
            return false;
        }

        if (storeAll) {
            for (XmiBufferItem item : xmiItemBuffer) {
                try {
                    DocumentId docId = item.getDocId();
                    byte[] completeXmiData = item.getXmiData();
                    Object storedData = handleDataZipping(completeXmiData, schemaDocument);
                    final String dataColumnName = dbc.getActiveTableFieldConfiguration().getFieldsToRetrieve().get(dbc.getActiveTableFieldConfiguration().getPrimaryKey().length).get(JulieXMLConstants.NAME);
                    annotationModules.add(new DocumentXmiData(dataColumnName, docId, storedData, null));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            if (useBinaryFormat) {
                // We will now, first, find missing mapping items relative to the currently known binary string mapping.
                // If we have missing items, we will add the XmiBufferItems that we currently have and in which we
                // found missing mapping items, to the 'xmiBufferItemsToProcess' map.
                // This map is open for processing to other threads, but may also end up being processed
                // by the current thread.
                TypeSystem ts = xmiItemBuffer.get(0).getTypeSystem();
                // Note the merging function at the end: If a document ID occurrs multiple times in the xmiItemBuffer (which actually can happen for
                // PubMed, for example, where the baseline may include some IDs multiple times in different versions), we take the latter one.
                // The hope is that this is the newer document or more recent version.
                final Map<DocumentId, XmiSplitterResult> splitterResultsToProcess = xmiItemBuffer.stream().collect(Collectors.toMap(XmiBufferItem::getDocId, XmiBufferItem::getSplitterResult, (res1, res2) -> res2));
                final BinaryStorageAnalysisResult requiredMappingAnalysisResult;
                // Check if we need new mappings at all
                final Pair<List<XmiBufferItem>, CountDownLatch> unanalyzedItems;
                boolean hasMissingMappingItems;
                synchronized (binaryMappedFeatures) {
                    unanalyzedItems = new ImmutablePair<>(xmiItemBuffer.stream().filter(Predicate.not(XmiBufferItem::isProcessedForBinaryMappings)).collect(Collectors.toList()), new CountDownLatch(2));
                    requiredMappingAnalysisResult = binaryEncoder.findMissingItemsForMapping(unanalyzedItems.getLeft().stream().flatMap(item -> item.getSplitterResult().jedisNodesInAnnotationModules.stream()).collect(Collectors.toList()), ts, binaryStringMapping.get(mappingCacheKey), binaryMappedFeatures.get(mappingCacheKey), featuresToMapDryRun);
                    // Here we add a list of XmiBufferItems that this thread needs processed to encode its annotation modules into the binary format.
                    hasMissingMappingItems = !requiredMappingAnalysisResult.getMissingValuesToMap().isEmpty();
                    if (hasMissingMappingItems)
                        xmiBufferItemsToProcess.compute(mappingCacheKey, (k, v) -> v != null ? v : new ConcurrentHashMap<>()).put(Thread.currentThread().getName(), unanalyzedItems);
                }
                if (hasMissingMappingItems) {
                    log.trace("Required mappings: {}", requiredMappingAnalysisResult.getMissingValuesToMap());
                    try {
                        while (unanalyzedItems.getRight().getCount() == 2) {
                            // Try to do the update with the current thread
                            if (!updateBinaryMapping(requiredMappingAnalysisResult, splitterResultsToProcess, ts)) {
                                // Wait if another thread is currently assembling the missing items, perhaps it will also get those of this thread
                                missingMappingsGatheringLatch.await();
                                // Now check if the items of this thread have been gotten by another thread
                                if (unanalyzedItems.getRight().getCount() == 2) {
                                    // If we couldn't do the update with this thread (return value false) and
                                    // this thread's missing items are not currently in process, wait for the current
                                    // update to finish and then do it with the current thread by waiting for the update lock
                                    mappingUpdateLock.lock();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        log.error("Interruption happened while waiting for another thread to gather the missing items of this thread ({}).", Thread.currentThread().getName());
                        throw new AnalysisEngineProcessException(e);
                    }
                    // It is possible that the current thread aquired the lock in the while loop above despite the
                    // fact that another thread had processed its items (another thread could get the lock and
                    // process this thread's missing items). In that case the while loop will be exited without
                    // calling the updateBinaryMapping() method and the lock wouldn't be unlocked so do it here.
                    if (mappingUpdateLock.isHeldByCurrentThread())
                        mappingUpdateLock.unlock();
                    try {
                        // Wait for this thread's items to be finished with the update. The current thread will
                        // actually wait here if another thread has promised to process the items by counting
                        // down the latch to 1. If the current thread did the update itself, the latch will
                        // already by at 0 here and there will be no wait time.
                        unanalyzedItems.getRight().await();
                    } catch (InterruptedException e) {
                        log.error("Interruption happened while waiting for another thread to update the missing binary mapping items for this thread ({}).", Thread.currentThread().getName());
                        throw new AnalysisEngineProcessException(e);
                    }
                } else {
                    // No mappings are missed, don't perform a mapping update.
                    // But remove the XmiBufferItems from the waiting list so that no unneeded work is done
                    // and also the waiting list does not grow to eternity.
                    unanalyzedItems.getLeft().stream().map(XmiBufferItem::getDocId).forEach(splitterResultMap.get(mappingCacheKey)::remove);
                }
            }

            createAnnotationModules();
        }
        xmiItemBuffer.clear();
        return true;
    }

    private boolean updateBinaryMapping(BinaryStorageAnalysisResult requiredMappingAnalysisResult, Map<DocumentId, XmiSplitterResult> splitterResultsToProcess, TypeSystem ts) throws AnalysisEngineProcessException {
        if (mappingUpdateLock.tryLock()) {
            try {
                // Here, we check for missing mappings for the whole buffers of all threads. This is important for performance
                // because each binary mapping update requires exclusive read/write access to the mapping table
                // in the database which is a potential bottleneck. Doing it batchwise alleviates this.
                missingMappingsGatheringLatch = new CountDownLatch(1);
                final List<XmiBufferItem> splitterResults = splitterResultMap.get(mappingCacheKey).keySet().stream().map(splitterResultMap.get(mappingCacheKey)::remove).filter(Objects::nonNull).collect(Collectors.toList());
                final List<XmiBufferItem> xmiBufferItemsFromOtherThreads = splitterResults.stream().filter(i -> !splitterResultsToProcess.containsKey(i.getDocId())).collect(Collectors.toList());
                final Collection<Pair<List<XmiBufferItem>, CountDownLatch>> xmiBufferItemsWaitedFor = new ArrayList<>(xmiBufferItemsToProcess.get(mappingCacheKey).values());
                xmiBufferItemsWaitedFor.stream().map(Pair::getLeft).flatMap(Collection::stream).forEach(xmiBufferItemsFromOtherThreads::add);
                xmiBufferItemsWaitedFor.stream().map(Pair::getRight).forEach(CountDownLatch::countDown);
                missingMappingsGatheringLatch.countDown();

                final List<JeDISVTDGraphNode> nodesFromOtherThreads = xmiBufferItemsFromOtherThreads.stream().flatMap(i -> i.getSplitterResult().jedisNodesInAnnotationModules.stream()).collect(Collectors.toList());
                log.trace("Got {} XmiBufferItems from other threads to check for missing mappings", xmiBufferItemsFromOtherThreads.size());
                final BinaryStorageAnalysisResult missingItemsForMapping = binaryEncoder.findMissingItemsForMapping(nodesFromOtherThreads, ts, binaryStringMapping.get(mappingCacheKey), binaryMappedFeatures.get(mappingCacheKey), featuresToMapDryRun);
                missingItemsForMapping.getMissingValuesToMap().addAll(requiredMappingAnalysisResult.getMissingValuesToMap());
                missingItemsForMapping.getMissingFeaturesToMap().putAll(requiredMappingAnalysisResult.getMissingFeaturesToMap());
                final Pair<Map<String, Integer>, Map<String, Boolean>> updatedMappingAndMappedFeatures = metaTableManager.updateBinaryStringMappingTable(missingItemsForMapping, binaryStringMapping.get(mappingCacheKey), binaryMappedFeatures.get(mappingCacheKey), !featuresToMapDryRun);
                synchronized (binaryMappedFeatures) {
                    binaryStringMapping.put(mappingCacheKey, Collections.synchronizedMap(updatedMappingAndMappedFeatures.getLeft()));
                    binaryMappedFeatures.put(mappingCacheKey, Collections.synchronizedMap(updatedMappingAndMappedFeatures.getRight()));
                }
                // Mark all the items as processed for other threads which might wait for them, otherwise.
                xmiBufferItemsFromOtherThreads.forEach(item -> item.setProcessedForBinaryMappings(true));
                log.debug("Releasing the locks of {} lists of XmiBufferItems to process", xmiBufferItemsWaitedFor.size());
                for (Pair<List<XmiBufferItem>, CountDownLatch> itemsWaitedFor : xmiBufferItemsWaitedFor) {
                    itemsWaitedFor.getLeft().forEach(item -> item.setProcessedForBinaryMappings(true));
                    itemsWaitedFor.getLeft().clear();
                    // Indicate that this list of items has been processed and the mapping is updated
                    itemsWaitedFor.getRight().countDown();
                }
                return true;
            } finally {
                mappingUpdateLock.unlock();
            }
        }
        return false;
    }

    private void createAnnotationModules() throws AnalysisEngineProcessException {
        log.debug("Creating annotation modules for {} items in the XMI buffer", xmiItemBuffer.size());
        for (int i = 0; i < xmiItemBuffer.size(); i++) {
            final XmiBufferItem item = xmiItemBuffer.get(i);
            DocumentId docId = item.getDocId();

            try {
                XmiSplitterResult result = xmiItemBuffer.get(i).getSplitterResult();
                Map<String, ByteArrayOutputStream> splitXmiData = result.xmiData;
                Integer newXmiId = result.maxXmiId;
                Map<Integer, String> currentSofaXmiIdMap = result.currentSofaIdMap;


                if (useBinaryFormat) {
                    try {
                        final Map<String, ByteArrayOutputStream> encodedXmiData = binaryEncoder.encode(result.jedisNodesInAnnotationModules, item.getTypeSystem(), binaryStringMapping.get(mappingCacheKey), binaryMappedFeatures.get(mappingCacheKey));
                        splitXmiData = encodedXmiData;
                    } catch (MissingBinaryMappingException e) {
                        throw new AnalysisEngineProcessException(e);
                    }
                }

                // adapt the map keys to table names (currently, the keys are the
                // Java type names)
                splitXmiData = convertModuleLabelsToColumnNames(splitXmiData);


                for (String columnName : splitXmiData.keySet()) {
                    boolean isBaseDocumentColumn = columnName.equals(XmiSplitConstants.BASE_DOC_COLUMN);
                    ByteArrayOutputStream dataBaos = splitXmiData.get(columnName);
                    if (null != dataBaos) {
                        byte[] dataBytes = dataBaos.toByteArray();
                        // Get the second field of the appropriate table schema,
                        // since the convention is that the data
                        // goes to the second column currently.
                        Object storedData = handleDataZipping(dataBytes, schemaDocument);
                        if (storeBaseDocument && isBaseDocumentColumn) {
                            annotationModules.add(new DocumentXmiData(XmiSplitConstants.BASE_DOC_COLUMN, docId, storedData, currentSofaXmiIdMap));
                        } else if (!isBaseDocumentColumn) {
                            annotationModules.add(new XmiData(columnName, docId, storedData));
                        }
                        annotationInserter.putXmiIdMapping(docId, newXmiId);
                        log.trace("{} has new value for column {} of length {}, new max xmi ID is {}", docId.getId(), columnName, dataBytes.length, newXmiId);
                    }
                }
                // as the very last thing, add this document to the processed list
                annotationInserter.addProcessedDocumentId(docId);
            } catch (IOException e) {
                throw new AnalysisEngineProcessException(e);
            }
        }
    }

    @Nullable
    private void serializeCasIntoBuffer(JCas aJCas, DocumentId docId) throws AnalysisEngineProcessException, SAXParseException {
        ByteArrayOutputStream baos;
        try {
            baos = new ByteArrayOutputStream();
            OutputStream os = baos;
            XmiCasSerializer.serialize(aJCas.getCas(), baos);
            os.close();

            int nextXmiId = determineNextXmiId(aJCas, docId);
            Map<String, Integer> baseDocumentSofaIdMap = getOriginalSofaIdMappings(aJCas, docId);
            // and now delete the XMI meta data
            Collection<XmiMetaData> xmiMetaData = JCasUtil.select(aJCas, XmiMetaData.class);
            if (xmiMetaData.size() > 1)
                throw new AnalysisEngineProcessException(new IllegalArgumentException(
                        "There are multiple XmiMetaData annotations in the cas for document " + docId + "."));
            xmiMetaData.forEach(XmiMetaData::removeFromIndexes);
            if (storeAll) {
                xmiItemBuffer.add(new XmiBufferItem(baos.toByteArray(), docId, baseDocumentSofaIdMap, nextXmiId, aJCas.getTypeSystem()));
            } else {
                XmiSplitterResult result = splitter.process(baos.toByteArray(), aJCas.getTypeSystem(), nextXmiId, baseDocumentSofaIdMap);
                final XmiBufferItem xmiBufferItem = new XmiBufferItem(result, docId, baseDocumentSofaIdMap, nextXmiId, aJCas.getTypeSystem());
                xmiItemBuffer.add(xmiBufferItem);
                if (useBinaryFormat) {
                    synchronized (splitterResultMap.get(mappingCacheKey)) {
                        splitterResultMap.get(mappingCacheKey).put(xmiBufferItem.getDocId(), xmiBufferItem);
                    }
                }

                if (!(featuresToMapDryRun && useBinaryFormat))
                    metaTableManager.manageXMINamespaces(result.namespaces);

                if (result.currentSofaIdMap.isEmpty())
                    throw new IllegalStateException(
                            "The XmiSplitter returned an empty Sofa XMI ID map. This is a critical errors since it means " +
                                    "that the splitter was not able to resolve the correct Sofa XMI IDs for the annotations " +
                                    "that should be stored now.");
            }
        } catch (SAXParseException e) {
            // we did have the issue that in PMID 23700993 there was an XMI-1.0
            // illegal character in the affiliation beginning with
            // "Department of Medical Informatics and Biostatistics, Iuliu
            // Haieganu University of Medicine and Phar"
            // Throwing an error does terminate the whole CPE which seems
            // unnecessary.
            log.error("Serialization error occurred, skipping this document: ", e);
            throw e;
        } catch (SAXException e) {
            e.printStackTrace();
            throw new AnalysisEngineProcessException(e);
        } catch (IOException e) {
            e.printStackTrace();
            throw new AnalysisEngineProcessException(e);
        } catch (XMISplitterException e) {
            throw new AnalysisEngineProcessException(e);
        }
    }

    @NotNull
    private Map<String, ByteArrayOutputStream> convertModuleLabelsToColumnNames(Map<String, ByteArrayOutputStream> splitXmiData) {
        Map<String, ByteArrayOutputStream> convertedMap = new HashMap<>();
        for (Entry<String, ByteArrayOutputStream> e : splitXmiData.entrySet()) {
            if (!e.getKey().equals(XmiSplitter.DOCUMENT_MODULE_LABEL))
                convertedMap.put(annotationTableManager.convertUnqualifiedAnnotationTypetoColumnName(e.getKey(), storeAll),
                        e.getValue());
            else
                convertedMap.put(XmiSplitConstants.BASE_DOC_COLUMN, e.getValue());
        }
        return convertedMap;
    }

    private DocumentId getDocumentId(JCas aJCas) {
        DocumentId docId = null;
        try {
            DBProcessingMetaData dbProcessingMetaData = JCasUtil.selectSingle(aJCas, DBProcessingMetaData.class);
            docId = new DocumentId(dbProcessingMetaData);
        } catch (IllegalArgumentException e) {
            // it seems there is not DBProcessingMetaData we could get a complex primary key from. The document ID
            // will have to do.
            log.trace("Could not find the primary key in the DBProcessingMetaData due to exception: {}. Using the document ID as primary key.", e.getMessage());
        }
        if (docId == null) {
            AnnotationIndex<Annotation> headerIndex = aJCas.getAnnotationIndex(Header.type);
            FSIterator<Annotation> headerIt = headerIndex.iterator();
            if (!headerIt.hasNext()) {
                int min = Math.min(100, aJCas.getDocumentText().length());
                log.warn(
                        "Got document without a header and without DBProcessingMetaData; cannot obtain document ID." +
                                " This document will not be written into the database. Document text begins with: {}",
                        aJCas.getDocumentText().substring(0, min));
                ++headerlessDocuments;
                return null;
            }

            Header header = (Header) headerIt.next();
            docId = new DocumentId(header.getDocId());
        }
        return docId;
    }

    /**
     * Reads all {@link XmiMetaData} types from the CAS index, writes the
     * actual sofa xmi:id to sofaID mapping into a map, removes the annotations
     * from the index and returns the map.
     *
     * @param aJCas
     * @param docId
     * @return
     */
    private Map<String, Integer> getOriginalSofaIdMappings(JCas aJCas, DocumentId docId) {
        if (storeAll)
            return Collections.emptyMap();
        XmiMetaData xmiMetaData;
        try {
            xmiMetaData = JCasUtil.selectSingle(aJCas, XmiMetaData.class);
            if (xmiMetaData.getSofaIdMappings() == null)
                return Collections.emptyMap();
        } catch (IllegalArgumentException e) {
            // in case there is no XMI meta data
            return Collections.emptyMap();
        }
        // note that we change the mapping orientation; originally stored
        // was xmiID:sofaID[sofaName]; but for the XmiSplitter input we need
        // it the other way round.
        Map<String, Integer> map = Stream.of(xmiMetaData.getSofaIdMappings().toArray()).map(line -> line.split("="))
                .collect(Collectors.toMap(split -> split[1], split -> Integer.parseInt(split[0])));
        log.trace("Got Sofa XMI map from the CAS: {} for document {}", map, docId);
        return map;
    }

    private int determineNextXmiId(JCas aJCas, DocumentId docId) throws AnalysisEngineProcessException {
        // Retrieve the max-xmi-id from the CAS, as this will have been
        // retrieved from the document table
        // via jules-cas-xmi-from-db-reader. In case the base document will be
        // stored for the first time
        // set it to 0, since it still has to be determined.
        int nextXmiId = 0;
        try {
            nextXmiId = JCasUtil.selectSingle(aJCas, XmiMetaData.class).getMaxXmiId();
        } catch (IllegalArgumentException e) {
            // If we store the base document and there is no current XmiMetaData
            // object, then we begin from scratch or want to overwrite the max
            // XMI ID on purpose. Don't throw an error then.
            if (!storeBaseDocument && !storeAll)
                throw new AnalysisEngineProcessException(new NullPointerException(
                        "Error: Could not find the max XMI ID in the CAS. Explanation: The option to store the base " +
                                "document (i.e. the document and possible same basic document meta " +
                                "data annotations) is set to false. Thus, it is assumed that the XMI DB Reader was used " +
                                "to read an existing base document and that only annotation data should be written now. In this " +
                                "case, the current maximum XMI ID for the respective document is required to be found " +
                                "in the CAS to keep this XMI ID unique for each annotation. This information is written " +
                                "into the CAS by the XMI DB Reader, if the " +
                                "respective configuration parameter is set to true. This seems not to be the case " +
                                "since the max XMI ID could not be found. Make sure that the reader adds the max XMI ID" +
                                "to the CAS and run the pipeline again."));
        }

        if (storeAll || storeBaseDocument || unqualifiedAnnotationNames.isEmpty()) {
            nextXmiId = 0;
            log.trace(
                    "Counting XMI IDs from 0 for document {} since the whole document is stored or the base document is stored or no additional annotations are stored.",
                    docId);
        } else {
            log.trace("Counting XMI IDs from {} for document {}.", nextXmiId, docId);
            if (nextXmiId == 0)
                log.warn(
                        "XMI IDs are counted from 0 for document {}. This is most probably a mistake since annotations should be stored but not the base document. In the base document are always some annotation elements with XMI IDs so those IDs will most probably already be taken and should not be assigned to new annotations.",
                        docId);
        }
        return nextXmiId;
    }

    /**
     * If <tt>doGzip</tt> is set to true, the <tt>dataBytes</tt> array will be
     * GZIPed. Otherwise, the data will be converted to a string so it can be
     * read directly from the database.
     *
     * @param dataBytes
     * @param tableSchemaName
     * @return
     * @throws IOException
     */
    protected Object handleDataZipping(byte[] dataBytes, String tableSchemaName) throws IOException {
        Object storedData;
        Map<String, String> field = dbc.getFieldConfiguration(tableSchemaName).getFields().get(1);
        String xmiFieldType = field.get(JulieXMLConstants.TYPE);
        if (doGzip || useBinaryFormat) {
            if (!xmiFieldType.equalsIgnoreCase("bytea"))
                log.warn("The table schema \"" + tableSchemaName + "\" specifies the data type \"" + xmiFieldType
                        + "\" for the field \"" + field.get(JulieXMLConstants.NAME)
                        + "\" which is supposed to be filled with gzipped XMI data. However, binary data should go to a field of type bytea.");
            if (doGzip) {
                ByteArrayOutputStream gzipBaos = new ByteArrayOutputStream();
                GZIPOutputStream gzos = new GZIPOutputStream(gzipBaos);
                gzos.write(dataBytes);
                gzos.close();
                storedData = gzipBaos.toByteArray();
            } else {
                // Unzipped binary format
                storedData = dataBytes;
            }
        } else {
            if (!xmiFieldType.equalsIgnoreCase("text") && !xmiFieldType.equalsIgnoreCase("xml"))
                log.warn("The table schema \"" + tableSchemaName + "\" specifies the data type \"" + xmiFieldType
                        + "\" for the field \"" + field.get(JulieXMLConstants.NAME)
                        + "\" and the contents to be written should be XML. Please use the field type xml or text for such contents.");
            storedData = new String(dataBytes, "UTF-8");
        }
        return storedData;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.uima.analysis_component.AnalysisComponent_ImplBase#
     * batchProcessComplete()
     */
    @Override
    public void batchProcessComplete() throws AnalysisEngineProcessException {
        super.batchProcessComplete();
        log.debug("Running batchProcessComplete.");
        if (splitterResultMap != null && splitterResultMap.get(mappingCacheKey).size() > 10000) {
            log.warn("The 'splitterResultMap' field has size {}. If this number does not shrink again, there is a memory leak.", splitterResultMap.get(mappingCacheKey).size());
        }
        if (xmiBufferItemsToProcess != null && xmiBufferItemsToProcess.get(mappingCacheKey).size() > 10000) {
            log.trace("Current size of 'xmiBufferItemsToProcess': {}", xmiBufferItemsToProcess.get(mappingCacheKey).size());
            log.warn("The 'xmiBufferITemsToProcess' field has size {}. If this number does not shrink again, there is a memory leak.", xmiBufferItemsToProcess.get(mappingCacheKey).size());
        }
        if (xmiItemBuffer.size() > 100000)
            log.warn("The 'xmiItemBuffer' field has size {}. If this number does not shrink again, there is a memory leak.", xmiItemBuffer.size());
        if (annotationModules.size() > 10000)
            log.warn("The 'annotationModules' field has size {}. If this number does not shrink again, there is a memory leak.", annotationModules.size());
        try {
            final boolean readyToSendData = processXmiBuffer();
            if (readyToSendData) {
                if (!(featuresToMapDryRun && useBinaryFormat))
                    annotationInserter.sendXmiDataToDatabase(effectiveDocTableName, annotationModules, subsetTable, storeBaseDocument, deleteObsolete, shaMap);
                else
                    log.info("The dry run to see details about features to be mapped in the binary format is activated. No contents are written into the database.");
                log.trace("Clearing {} annotation modules", annotationModules.size());
                annotationModules.clear();
                if (shaMap != null)
                    shaMap.clear();
                if (docReleaseCheckpoint != null)
                    docReleaseCheckpoint.release(jedisSyncKey, currentDocumentIdBatch.stream());
                currentDocumentIdBatch.clear();
            }
        } catch (XmiDataInsertionException e) {
            throw new AnalysisEngineProcessException(e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.uima.analysis_component.AnalysisComponent_ImplBase#
     * collectionProcessComplete()
     */
    @Override
    public void collectionProcessComplete() throws AnalysisEngineProcessException {
        super.collectionProcessComplete();
        log.debug("Running collectionProcessComplete.");
        try {
            processXmiBuffer();
            if (!(featuresToMapDryRun && useBinaryFormat))
                annotationInserter.sendXmiDataToDatabase(effectiveDocTableName, annotationModules, subsetTable, storeBaseDocument, deleteObsolete, shaMap);
            else
                log.info("The dry run to see details about features to be mapped in the binary format is activated. No contents are written into the database.");
            annotationModules.clear();
            if (shaMap != null)
                shaMap.clear();
            if (docReleaseCheckpoint != null)
                docReleaseCheckpoint.release(jedisSyncKey, currentDocumentIdBatch.stream());
            currentDocumentIdBatch.clear();
        } catch (XmiDataInsertionException e) {
            throw new AnalysisEngineProcessException(e);
        }
        log.info("{} documents without a head occured overall. Those could not be written into the database.",
                headerlessDocuments);
        dbc.close();
    }

}