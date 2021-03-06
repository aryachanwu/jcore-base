package de.julielab.jcore.consumer.es.sharedresources;

import de.julielab.jcore.utility.JCoReTools;
import org.apache.uima.resource.DataResource;
import org.apache.uima.resource.ResourceInitializationException;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

public class MapDBReversedDoubleMapProvider implements IMapProvider<String, Double> {

    private Map<String, Double> map;

    @Override
    public void load(DataResource aData) throws ResourceInitializationException {
        BufferedReader br = null;
        try {
            final DB filedb = DBMaker.tempFileDB().fileMmapEnableIfSupported().cleanerHackEnable().closeOnJvmShutdownWeakReference().make();
            map = filedb.hashMap("JCoReElasticSearchReverseMapProvider").
                    keySerializer(Serializer.STRING).valueSerializer(Serializer.DOUBLE).
                    create();
            InputStreamReader is;
            try {
                is = new InputStreamReader(JCoReTools.resolveExternalResourceGzipInputStream(aData));
            } catch (Exception e) {
                throw new IOException("Resource " + aData.getUri() + " not found");
            }
            br = new BufferedReader(is);
            String line;
            String splitExpression = "\t";
            while ((line = br.readLine()) != null) {
                if (line.trim().length() == 0 || line.startsWith("#"))
                    continue;
                String[] split = line.split(splitExpression);
                if (split.length != 2) {
                    splitExpression = "\\s+";
                    split = line.split(splitExpression);
                }
                if (split.length != 2)
                    throw new IllegalArgumentException("Format error in map file: Expected format is 'originalValue<tab>mappedValue' but the input line '" + line
                            + "' has " + split.length + " columns.");
                map.put(split[1].trim(), Double.parseDouble(split[0].trim()));
            }
        } catch (IOException e) {
            throw new ResourceInitializationException(e);
        } finally {
            try {
                if (null != br)
                    br.close();
            } catch (IOException e) {
                throw new ResourceInitializationException(e);
            }
        }

    }

    /**
     * Returns the loaded map. All strings - keys and values - are internalized.
     */
    @Override
    public Map<String, Double> getMap() {
        return map;
    }

}
