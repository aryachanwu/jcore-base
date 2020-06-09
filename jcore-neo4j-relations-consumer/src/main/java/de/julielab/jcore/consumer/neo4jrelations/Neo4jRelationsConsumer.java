package de.julielab.jcore.consumer.neo4jrelations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import de.julielab.jcore.types.ArgumentMention;
import de.julielab.jcore.types.ConceptMention;
import de.julielab.jcore.types.ResourceEntry;
import de.julielab.jcore.types.ext.FlattenedRelation;
import de.julielab.jcore.utility.JCoReTools;
import de.julielab.neo4j.plugins.datarepresentation.ImportIERelation;
import de.julielab.neo4j.plugins.datarepresentation.ImportIERelationArgument;
import de.julielab.neo4j.plugins.datarepresentation.ImportIERelationDocument;
import de.julielab.neo4j.plugins.datarepresentation.ImportIETypedRelations;
import de.julielab.neo4j.plugins.datarepresentation.constants.ImportIERelations;
import org.apache.commons.io.IOUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.ResourceMetaData;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.resource.ResourceInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.HttpMethod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.stream.StreamSupport;

import static java.nio.charset.StandardCharsets.UTF_8;

@ResourceMetaData(name = "JCoRe Neo4j Relations Consumer", description = "This component assumes that a Neo4j server with an installed julieliab-neo4j-plugins-concepts plugin installed. It then sends FlattenedRelation instances with more then one arguments to Neo4j. Note that this requires the event arguments to have a ResourceEntry list to obtain database concept IDs from.", vendor = "JULIE Lab, Germany", copyright = "JULIE Lab", version = "2.6.0-SNAPSHOT")
@TypeCapability(inputs = {"de.julielab.jcore.types.EventMention"})
public class Neo4jRelationsConsumer extends JCasAnnotator_ImplBase {

    public static final String PARAM_URL = "URL";
    public static final String PARAM_ID_PROPERTY = "IdProperty";
    public static final String PARAM_SOURCE = "ConceptSource";
    private final static Logger log = LoggerFactory.getLogger(Neo4jRelationsConsumer.class);
    @ConfigurationParameter(name = PARAM_URL, description = "The complete URL to the endpoint of the Neo4j server for relation insertion.")
    private String url;
    @ConfigurationParameter(name = PARAM_ID_PROPERTY, description = "The ID property to look up concept nodes in the Neo4j graph. Common options are 'id', 'sourceIds' and 'originalId'. You must know to which ID type the ResourceEntry objects of the relation arguments refer to.")
    private String idProperty;
    @ConfigurationParameter(name = PARAM_SOURCE, mandatory = false, description = "Optional. Sets the global source for the concept IDs taken from the ResourceEntry instances of the relation arguments. This causes the 'source' feature of the ResourceEntry objects to be omitted and to globally use the specified source instead. This causes the Neo4j database plugin to resolve the provided argument IDs against the source specified here.")
    private String globalSource;

    private ImportIERelations importIERelations;
    private ObjectMapper om;

    /**
     * This method is called a single time by the framework at component
     * creation. Here, descriptor parameters are read and initial setup is done.
     */
    @Override
    public void initialize(final UimaContext aContext) throws ResourceInitializationException {
        url = (String) aContext.getConfigParameterValue(PARAM_URL);
        idProperty = (String) aContext.getConfigParameterValue(PARAM_ID_PROPERTY);
        globalSource = Optional.ofNullable((String) aContext.getConfigParameterValue(PARAM_SOURCE)).orElse(null);
        om = new ObjectMapper();
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        om.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        initImportRelations();
    }

    private void initImportRelations() {
        importIERelations = globalSource != null ? new ImportIERelations(idProperty, globalSource) : new ImportIERelations(idProperty);
    }

    /**
     * This method is called for each document going through the component. This
     * is where the actual work happens.
     */
    @Override
    public void process(final JCas aJCas) {
        importIERelations.addRelationDocument(convertRelations(aJCas));
    }

    private ImportIERelationDocument convertRelations(JCas aJCas) {
        Map<String, Multiset<UnificationRelation>> relationCounts = getEquivalentRelationGroups(aJCas);
        ImportIERelationDocument relDoc = new ImportIERelationDocument();
        relDoc.setDb(false);
        relDoc.setName(JCoReTools.getDocId(aJCas));
        ImportIETypedRelations typedRelations = new ImportIETypedRelations();
        for (String relationType : relationCounts.keySet()) {
            Multiset<UnificationRelation> unificationRelations = relationCounts.get(relationType);
            List<ImportIERelation> ieRelations4relationType = new ArrayList<>();
            for (UnificationRelation rel : unificationRelations.elementSet()) {
                ieRelations4relationType.add(rel.toImportRelation(unificationRelations.count(rel)));
            }
            typedRelations.put(relationType, ieRelations4relationType);
        }
        relDoc.setRelations(typedRelations);
        return relDoc;
    }

    @Override
    public void batchProcessComplete() throws AnalysisEngineProcessException {
        super.batchProcessComplete();
        sendRelationsToNeo4j();
    }

    @Override
    public void collectionProcessComplete() throws AnalysisEngineProcessException {
        super.collectionProcessComplete();
        sendRelationsToNeo4j();
    }

    private void sendRelationsToNeo4j() throws AnalysisEngineProcessException {
        try {
            URL url = URI.create(this.url).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.addRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestMethod(HttpMethod.POST);
            urlConnection.setDoOutput(true);
            try (OutputStream outputStream = urlConnection.getOutputStream()) {
                JsonFactory jf = new JsonFactory(om);
                JsonGenerator g = jf.createGenerator(outputStream);
                g.writeStartObject();
                g.writeObjectField(ImportIERelations.NAME_ID_PROPERTY, idProperty);
                g.writeObjectField(ImportIERelations.NAME_ID_SOURCE, globalSource);

                List<ImportIERelationDocument> documents = importIERelations.getDocuments();
                g.writeFieldName(ImportIERelations.NAME_DOCUMENTS);
                g.writeStartArray();
                for (ImportIERelationDocument document : (Iterable<ImportIERelationDocument>) documents::iterator) {
                    g.writeObject(document);
                }
                g.writeEndArray();
                g.writeEndObject();
                g.close();
            }
            try (InputStream inputStream = urlConnection.getInputStream()) {
                log.debug("Response from Neo4j: {}", IOUtils.toString(inputStream, UTF_8));
            } catch (IOException e) {
                log.error("Exception occurred while sending relation data to Neo4j server.");
                try (InputStream inputStream = urlConnection.getErrorStream()) {
                    if (inputStream != null)
                        log.error("Error from Neo4j: {}", IOUtils.toString(inputStream, UTF_8));
                }
                throw e;
            }
            importIERelations.clear();
        } catch (IOException e) {
            log.error("Could not send relations to Neo4j", e);
            throw new AnalysisEngineProcessException(e);
        }
    }

    /**
     * <p>Iterates through the FlattenedRelations in the JCas and creates an intermediate representation that is primarily meant to group relations together that are basically the same. Then we can just count them instead of sending duplicates to the server.</p>
     *
     * @param aJCas The JCas to get relations from.
     * @return The grouped relations.
     */
    private Map<String, Multiset<UnificationRelation>> getEquivalentRelationGroups(JCas aJCas) {
        Map<String, Multiset<UnificationRelation>> relationCounts = new HashMap<>();
        for (FlattenedRelation fr : aJCas.<FlattenedRelation>getAnnotationIndex(FlattenedRelation.type)) {
            Iterator<ConceptMention> cmIt = StreamSupport.stream(fr.getArguments().spliterator(), false)
                    .map(ArgumentMention.class::cast)
                    .map(ArgumentMention::getRef)
                    .map(ConceptMention.class::cast)
                    .iterator();
            Set<UnificationArgument> unificationArgs = new HashSet<>();
            while (cmIt.hasNext()) {
                ConceptMention cm = cmIt.next();
                FSArray resourceEntryList = cm.getResourceEntryList();
                if (resourceEntryList != null) {
                    ResourceEntry resourceEntry = (ResourceEntry) resourceEntryList.get(0);
                    String id = resourceEntry.getEntryId();
                    String source = resourceEntry.getSource();
                    if (globalSource == null)
                        unificationArgs.add(new UnificationArgument(id, source));
                    else
                        unificationArgs.add(new UnificationArgument(id));
                }
            }
            if (unificationArgs.size() > 1) {
                UnificationRelation rel = new UnificationRelation(fr.getRootRelation().getSpecificType(), unificationArgs);
                relationCounts.compute(rel.getRelationType(), (k, v) -> v != null ? v : HashMultiset.create()).add(rel);
            }
        }
        return relationCounts;
    }

    private class UnificationRelation {
        private String relationType;
        private Set<UnificationArgument> args;

        public UnificationRelation(String relationType, Set<UnificationArgument> args) {
            this.relationType = relationType;
            this.args = args;
        }

        public ImportIERelation toImportRelation(int count) {
            return ImportIERelation.of(count, () -> args.stream().map(UnificationArgument::toImportArgument).iterator());
        }

        public String getRelationType() {
            return relationType;
        }

        public Set<UnificationArgument> getArgs() {
            return args;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UnificationRelation that = (UnificationRelation) o;
            return relationType.equals(that.relationType) &&
                    args.equals(that.args);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relationType, args);
        }
    }

    private class UnificationArgument {
        private String id;
        private String source;

        public UnificationArgument(String id) {
            this.id = id;
        }

        public UnificationArgument(String id, String source) {
            this.id = id;
            this.source = source;
        }

        public ImportIERelationArgument toImportArgument() {
            return source != null ? ImportIERelationArgument.of(id, source) : ImportIERelationArgument.of(id);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UnificationArgument that = (UnificationArgument) o;
            return id.equals(that.id) &&
                    Objects.equals(source, that.source);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, source);
        }

        public String getId() {
            return id;
        }

        public String getSource() {
            return source;
        }
    }


}
