package uk.bl.wa.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class ExternalServiceSolrFieldEnricherIntegrationTest {

    /**
     *  Convenience main method to manual call and test an external webservice. Easy to run from IDE.
     *  The method will do the same steps as the the getSolrEnrichmentFields method in ExternalServiceSolrFieldEnricher.
     *   
     */
    public static void main(String[] args) throws Exception{    
        Config conf = ConfigFactory.load(); //This will load the reference.conf file.
        
        List<String> solrField2JsonFields= conf.getStringList("warc.enrich.solrField2JsonFields");
        List<String> jsonFields2SolrFieldsList = conf.getStringList("warc.enrich.jsonFields2SolrFields");                        
        
        System.out.println("solrField2Json fields mapping:");
        for (String map:solrField2JsonFields) {
            System.out.println(map);
        }
        
        System.out.println("jsonField2SolrField mapping:");
        for (String map: jsonFields2SolrFieldsList) {
            System.out.println(map);
        }
        
        String serverUrl= conf.getString("warc.enrich.server_url");                        
        System.out.println("Service url:"+serverUrl);
        ExternalServiceSolrFieldEnricher enrichService = new ExternalServiceSolrFieldEnricher(serverUrl,  solrField2JsonFields,jsonFields2SolrFieldsList);          
        
        //Test single warc entry. Change for test
        HashMap<String,String> jsonRequestParameters = new HashMap<String,String>();
        jsonRequestParameters.put("file_path","/home/teg/solrwayback/warcs_webkid_filtered/DENMARK-EXTRACTED-2000-part-00000003.warc");
        jsonRequestParameters.put("offset","682189");
        
        String json= enrichService.generateJsonObject(jsonRequestParameters);               
        System.out.println("POST json to service:"+json);
        String jsonResponse = enrichService.callService( json);
        System.out.println("Service response:"+jsonResponse);
        HashMap<String, String> jsonFromService = enrichService.parseJsonMapToJavaMap(jsonResponse);        
        System.out.println("Json fields in response:"+jsonFromService);
        HashMap<String, String> solrFields = enrichService.extractSolrFieldsFromResponse(jsonFromService);
        System.out.println("solr fields:"+solrFields);
        
        
    }

}
