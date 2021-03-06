package de.julielab.jcore.consumer.xmi;

import com.google.common.collect.Sets;
import de.julielab.costosys.Constants;
import de.julielab.costosys.configuration.FieldConfig;
import de.julielab.costosys.dbconnection.CoStoSysConnection;
import de.julielab.costosys.dbconnection.DataBaseConnector;
import de.julielab.jcore.ae.checkpoint.DocumentId;
import de.julielab.xml.JulieXMLConstants;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class XmiDataInserter {

    public static final String FIELD_MAX_XMI_ID = "max_xmi_id";
    private static final Logger log = LoggerFactory.getLogger(XmiDataInserter.class);
    private Boolean updateMode;
    private String schemaDocument;
    private Boolean storeAll;
    private Set<String> annotationModuleColumnNames;
    private DataBaseConnector dbc;
    private Map<DocumentId, Integer> maxXmiIdMap;
    private String componentDbName;
    private String hashColumnName;

    private List<DocumentId> processedDocumentIds;

    public XmiDataInserter(Set<String> annotationModuleColumnNames,
                           DataBaseConnector dbc, String schemaDocument, Boolean storeAll,
                           Boolean updateMode, String componentDbName, String hashColumnName) {
        super();
        this.annotationModuleColumnNames = annotationModuleColumnNames;
        this.dbc = dbc;
        this.schemaDocument = schemaDocument;
        this.storeAll = storeAll;
        this.updateMode = updateMode;
        this.componentDbName = componentDbName;
        this.hashColumnName = hashColumnName;
        this.maxXmiIdMap = new HashMap<>();
        this.processedDocumentIds = new ArrayList<>();
    }

    /**
     * Constructs row iterators for the different tables (document and
     * annotations) conforming to the expectations of the DataBaseConnector API.
     * If update mode is <code>true</code>, the CAS data will be added as an
     * update. It will just be inserted otherwise (throwing an error if there
     * will be a primary key constraint violation, i.e. duplicates).
     *
     * @param serializedCASes
     * @param storeBaseDocument
     * @param deleteObsolete
     * @param shaMap
     * @throws XmiDataInsertionException
     * @throws AnalysisEngineProcessException
     */
    public void sendXmiDataToDatabase(String xmiTableName, List<XmiData> serializedCASes, String subsetTableName, Boolean storeBaseDocument, Boolean deleteObsolete, Map<DocumentId, String> shaMap) throws XmiDataInsertionException {
        if (log.isTraceEnabled()) {
            log.trace("Sending XMI data for {} tables to the database", serializedCASes.size());
            log.trace("Sending {} XMI data items", serializedCASes.size());
        }
        final Map<DocumentId, List<XmiData>> dataByDoc = serializedCASes.stream().collect(Collectors.groupingBy(XmiData::getDocId));
        final Set<DocumentId> documentIdsWithValues = shaMap != null ? Sets.union(dataByDoc.keySet(), shaMap.keySet()) : dataByDoc.keySet();
        class RowIterator implements Iterator<Map<String, Object>> {

            private Iterator<DocumentId> docIdIterator = documentIdsWithValues.iterator();
            private FieldConfig fieldConfig = dbc.getFieldConfiguration(schemaDocument);
            private List<Map<String, String>> fields = fieldConfig.getFields();

            @Override
            public boolean hasNext() {
                return docIdIterator.hasNext();
            }

            @Override
            public Map<String, Object> next() {
                Map<String, Object> row = new HashMap<String, Object>();
                final DocumentId docId = docIdIterator.next();
                // There might actually be no data when we only write the SHA hashes
                final List<XmiData> dataList = dataByDoc.getOrDefault(docId, Collections.emptyList());
                // this lambda says "give me the name of ith field of the
                // current field configuration"
                Function<Integer, String> fName = num -> fields.get(num).get(JulieXMLConstants.NAME);

                // We need to fill the, possibly complex, primary key with the values from the actual
                // document ID. We here assume that the order of document ID elements directly corresponds
                // to the order of the primary key definition in the field configuration.
                int i = 0;
                for (Integer pkIndex : fieldConfig.getPrimaryKeyFieldNumbers()) {
                    row.put(fName.apply(pkIndex), docId.getId()[i++]);
                    if (log.isTraceEnabled())
                        log.trace("{}={}", fName.apply(pkIndex), row.get(fName.apply(pkIndex)));
                }
                // This statement puts the XMI data into the first column after the primary key
                for (XmiData data : dataList) {
                    row.put(data.getColumnName(), data.data);
                    if (log.isTraceEnabled()) {
                        String datarep = data.toString();
                        if (data.data instanceof byte[])
                            datarep = "byte array of length " + ((byte[]) data.data).length;
                        if (datarep.length() > 79)
                            datarep = datarep.substring(0, 80);
                        log.trace("{}={}", data.getColumnName(), datarep);
                    }
                    // If the base document is stored, update the sofa mapping (which sofa ID is which sofa name in the base document)
                    if (data.getClass().equals(DocumentXmiData.class) && !storeAll) {
                        if (fieldConfig.getFields().size() - fieldConfig.getPrimaryKey().length < 3)
                            throw new IllegalArgumentException("The XMI data table schema is set to the schema with name " +
                                    "\"" + schemaDocument + "\" that specifies the fields \"" +
                                    StringUtils.join(fieldConfig.getColumns(), ",") + "\". However, this schema " +
                                    "is not compatible with XMI base " +
                                    "document storage since the storage requires two extra fields to store the maximum XMI " +
                                    "ID of the document and the sofa mapping.");
                        DocumentXmiData docResults = (DocumentXmiData) data;
                        row.put("sofa_mapping", docResults.serializedSofaXmiIdMap);
                        log.trace("{}={}", "sofa_mapping", docResults.serializedSofaXmiIdMap);
                    }
                }
                // Also update the new max XMI ID
                final Integer maxXmiId = maxXmiIdMap.get(docId);
                if (maxXmiId != null)
                    row.put(FIELD_MAX_XMI_ID, maxXmiId);
                // Set columns without values explicitly to null. This will automatically remove old column values.
                if (deleteObsolete) {
                    Set<String> missingColumns = fieldConfig.getFields().stream().map(f -> f.get(JulieXMLConstants.NAME)).collect(Collectors.toSet());
                    for (String filledColumn : row.keySet())
                        missingColumns.remove(filledColumn);
                    missingColumns.forEach(c -> row.put(c, null));
                }
                // Set columns without a value to null to delete a potentially existing value.
                if (updateMode) {
                    Set<String> annotationColumnsWithValues = dataList.stream().map(XmiData::getColumnName).collect(Collectors.toSet());
                    final Sets.SetView<String> columnsWithoutValues = Sets.difference(annotationModuleColumnNames, annotationColumnsWithValues);
                    columnsWithoutValues.forEach(col -> {
                        row.put(col, null);
                        log.trace("{}=null", col);
                    });
                }
                if (shaMap != null && !shaMap.isEmpty()) {
                    final String hash = shaMap.get(docId);
                    row.put(hashColumnName, hash);
                    log.trace("{}={}", hashColumnName, hash);
                }
                return row;
            }

            @Override
            public void remove() {
                throw new NotImplementedException();
            }
        }

        try (CoStoSysConnection conn = dbc.obtainOrReserveConnection()) {
            conn.setAutoCommit(false);

            RowIterator iterator = new RowIterator();
            try {
                if (updateMode) {
                    log.debug("Updating {} XMI CAS data in database table '{}'.",
                            serializedCASes.size(), xmiTableName);
                    dbc.updateFromRowIterator(iterator, xmiTableName, false, storeBaseDocument, schemaDocument);
                } else {
                    log.debug("Inserting {} XMI CAS data into database table '{}'.",
                            serializedCASes.size(), xmiTableName);
                    dbc.importFromRowIterator(iterator, xmiTableName, false, schemaDocument);
                }
            } catch (Exception e) {
                log.error("Error occurred while sending data to database. Exception:", e);
                throw new XmiDataInsertionException(e);
            }
            setLastComponent(conn, subsetTableName);
            log.debug("Committing XMI data to database.");
            conn.commit();
            maxXmiIdMap.clear();
        } catch (SQLException e) {
            log.error("Database error occurred while updating max-xmi-IDs: {}", e);
            e.printStackTrace();
            SQLException ne = e.getNextException();
            if (null != ne)
                ne.printStackTrace();
        }
    }

    /**
     * Writes the component name to the database subset
     *
     * @param conn
     * @throws XmiDataInsertionException
     */
    private void setLastComponent(CoStoSysConnection conn, String subsetTableName) throws XmiDataInsertionException {
        if (processedDocumentIds.isEmpty() || StringUtils.isBlank(subsetTableName))
            return;

        FieldConfig annotationFieldConfig = dbc.getFieldConfiguration(schemaDocument);
        String[] primaryKey = annotationFieldConfig.getPrimaryKey();
        if (primaryKey.length > 1)
            throw new IllegalArgumentException("Currently, only one-element primary keys are supported.");
        // create a string for the prepared statement in the form "pk1 = ? AND pk2 = ? ..."
        String primaryKeyPsString = StringUtils.join(annotationFieldConfig.expandPKNames("%s = ?"), " AND ");

        log.debug("Marking {} documents to having been processed by component \"{}\".", processedDocumentIds.size(), componentDbName);

        String sql = String.format("UPDATE %s SET %s='%s' WHERE %s", subsetTableName, Constants.LAST_COMPONENT, componentDbName, primaryKeyPsString);

        try {
            boolean tryagain;
            do {
                tryagain = false;
                PreparedStatement ps = conn.prepareStatement(sql);
                for (DocumentId docId : processedDocumentIds) {
                    for (int i = 0; i < docId.getId().length; i++) {
                        String pkElement = docId.getId()[i];
                        ps.setString(i + 1, pkElement);
                    }
                    ps.addBatch();
                }
                try {
                    ps.executeBatch();
                } catch (BatchUpdateException e) {
                    if (e.getMessage().contains("deadlock detected")) {
                        log.debug("Database transaction deadlock detected while trying to set the last component. Trying again.");
                        tryagain = true;
                    }
                }
            } while (tryagain);
        } catch (SQLException e) {
            e.printStackTrace();
            SQLException nextException = e.getNextException();
            if (null == nextException)
                throw new XmiDataInsertionException(e);
            else
                nextException.printStackTrace();
            throw new XmiDataInsertionException(nextException);
        } finally {
            processedDocumentIds.clear();
        }
    }


    public void putXmiIdMapping(DocumentId docId, Integer newXmiId) {
        maxXmiIdMap.put(docId, newXmiId);
    }

    public void addProcessedDocumentId(DocumentId docId) {
        processedDocumentIds.add(docId);
    }

}
