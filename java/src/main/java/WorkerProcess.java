/**
 * Copyright (c) 2012, FinancialForce.com, inc
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 *   are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *      this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *      this list of conditions and the following disclaimer in the documentation 
 *      and/or other materials provided with the distribution.
 * - Neither the name of the FinancialForce.com, inc nor the names of its contributors 
 *      may be used to endorse or promote products derived from this software without 
 *      specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES 
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL 
 *  THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
 *  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 *  OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 *  OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
**/

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.client.ClientSessionChannel.MessageListener;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.ajax.JSON;

import com.google.gson.Gson;
import com.sforce.rest.RestApiException;
import com.sforce.rest.RestConnection;
import com.sforce.rest.RestConnectionImpl;
import com.sforce.soap.metadata.AsyncResult;
import com.sforce.soap.metadata.CustomObject;
import com.sforce.soap.metadata.ListView;
import com.sforce.soap.metadata.ListViewFilter;
import com.sforce.soap.metadata.MetadataConnection;
import com.sforce.soap.metadata.PackageTypeMembers;
import com.sforce.soap.metadata.RetrieveRequest;
import com.sforce.soap.metadata.RetrieveResult;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.LoginResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.bind.TypeMapper;
import com.sforce.ws.parser.XmlInputStream;

public class WorkerProcess 
{
	// Salesforce API's
	private static final String SALESFORCE_API = "25.0";
    private static final String LOGIN_ENDPOINT = "https://login.salesforce.com/services/Soap/u/" + SALESFORCE_API;
    private static final String REST_ENDPOINT_URI = "/services/data/v" + SALESFORCE_API + "/";
    private static final String STREAMING_ENDPOINT_URI = "/cometd/" + SALESFORCE_API;

    /**
     * Main entry point for the Heroku Worker Process
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception 
    {    			
    	// TODO: Login via oAuth
		ConnectorConfig partnerConfig = new ConnectorConfig();
		partnerConfig.setManualLogin(true); // Manual login to obtain the MetadataServerUrl from LoginInfo
		partnerConfig.setServiceEndpoint(LOGIN_ENDPOINT);
		PartnerConnection partnerConnection = Connector.newConnection(partnerConfig);
		final LoginResult loginResult = partnerConnection.login(System.getenv("SFUSER"), System.getenv("SFPASSWORD"));         
		partnerConfig.setServiceEndpoint(loginResult.getServerUrl());
		partnerConfig.setSessionId(loginResult.getSessionId());
		partnerConnection.setSessionHeader(loginResult.getSessionId());
    	
        // Connect to Salesforce Streaming API
        final BayeuxClient client = makeStreamingAPIConnection(loginResult);

        // Subscribe to the Process__c topic to listen for new or updated Processes
        System.out.println("Subscribing for channel: /topic/processchanges");
        client.getChannel("/topic/processchanges").subscribe(new MessageListener() 
	        {
	            @SuppressWarnings("unchecked")
				@Override     
	            public void onMessage(ClientSessionChannel channel, Message message) 
	            {
	                try
	                {
		                System.out.println("Received Message: " + message);
	            		HashMap<String, Object> data = (HashMap<String, Object>) JSON.parse(message.toString());
	            		HashMap<String, Object> record = (HashMap<String, Object>) data.get("data");
	            		HashMap<String, Object> sobject = (HashMap<String, Object>) record.get("sobject");
	            		HashMap<String, Object> event = (HashMap<String, Object>) record.get("event");
	            		// Event type, insert or update?
	            		String type = (String) event.get("type");	            		
	            		if(type.equals("created"))
	            			processInsert(loginResult, client, sobject);
	                }
	                catch (Exception e)
	                {
	                	System.err.println(e.getMessage());
                        System.exit(1);
	                }
	            }
	        });  
        
        // Subscribe to existing PushTopic's defined via Process__c records
        QueryResult result = partnerConnection.query("SELECT Id, Name, SourceObject__c, PushTopicName__c FROM Process__c Where PushTopicName__c !=''");
        for(SObject record : result.getRecords())
        {        	
        	// TODO: Prefer to use POJO here
        	String processId = (String) record.getField("Id");
        	String processName = (String) record.getField("Name");
        	String sourceObject = (String) record.getField("SourceObject__c");
        	String topicName = (String) record.getField("PushTopicName__c");
        	String channel = "/topic/"+topicName;
            System.out.println("Subscribing for channel: " + channel + " (" + processName + ")");        	
        	client.getChannel(channel).subscribe(new SourceObjectListener(loginResult, processId, sourceObject));
        }        
    }
    	
    /**
     * Handles inserts on the Process__c custom object
     * @param client
     * @param sObject
     * @throws Exception
     */
	public static void processInsert(LoginResult loginResult, BayeuxClient client, HashMap<String, Object> sObject)
		throws Exception
	{	
		// Connection configuration
		ConnectorConfig metadataConfig = new ConnectorConfig();
		metadataConfig.setSessionId(loginResult.getSessionId());
		metadataConfig.setServiceEndpoint(loginResult.getMetadataServerUrl());
		MetadataConnection metadataConnection = com.sforce.soap.metadata.Connector.newConnection(metadataConfig);
		
		// Make a REST connection
        RestConnection restConnection = makeRestConnection(loginResult);
        
		// TODO: Prefer to use a POJO here
		String processId = (String) sObject.get("Id");
		String processName = (String) sObject.get("Name");
		String sourceObject = (String) sObject.get("SourceObject__c");
		String listViewName = (String) sObject.get("ListViewName__c");
		
		try
		{	                
	        // Log output
			System.out.println("Process " + processId + " has changed.");			
	        logMessage(restConnection, processId, "Creating Push Topic for " + sourceObject);        
	        
			// Retrieve Custom Object Meta data for Source Object
			RetrieveRequest retrieveRequest = new RetrieveRequest();
			retrieveRequest.setSinglePackage(true);
			com.sforce.soap.metadata.Package packageManifest = new com.sforce.soap.metadata.Package();		
			ArrayList<PackageTypeMembers> types = new ArrayList<PackageTypeMembers>();
			PackageTypeMembers packageTypeMember = new PackageTypeMembers();
			packageTypeMember.setName("CustomObject");
			packageTypeMember.setMembers(new String[] { sourceObject });
			types.add(packageTypeMember);
			packageManifest.setTypes((PackageTypeMembers[]) types.toArray(new PackageTypeMembers[] {}));
			retrieveRequest.setUnpackaged(packageManifest);			
			AsyncResult response = metadataConnection.retrieve(retrieveRequest);
			while(!response.isDone())
			{
				Thread.sleep(1000);
				response = metadataConnection.checkStatus(new String[] { response.getId()} )[0];
			}			
			RetrieveResult retrieveResult = metadataConnection.checkRetrieveStatus(response.getId());
			
			// Parse Custom Object Meta Data for Source Object
			CustomObject customObject = new CustomObject();		
			byte[] zipBytes = retrieveResult.getZipFile();
			ZipInputStream zipis = new ZipInputStream(new ByteArrayInputStream(zipBytes, 0, zipBytes.length));
			ZipEntry zipEntry = null;
			while((zipEntry = zipis.getNextEntry()) != null)
			{
				if(zipEntry.getName().endsWith(sourceObject + ".object"))
				{
					TypeMapper typeMapper = new TypeMapper();
					XmlInputStream xmlis = new XmlInputStream();
					xmlis.setInput(zipis, "UTF-8");
					customObject.load(xmlis, typeMapper);
					zipis.closeEntry();
					break;
				}
			}
			
			// Find the List View
			ListView processlistView = null;
			for(ListView listView : customObject.getListViews())
			{
				if(listView.getFullName().equals(listViewName))
				{
					processlistView = listView;
					break;
				}
			}
			if(processlistView==null)
				throw new Exception("Unable to find the List View named " + listViewName);
			
			// Generate SOQL statement for PushTopic based on List View definition
			StringBuilder fieldList = new StringBuilder("Id");
			for(String field : processlistView.getColumns())
				fieldList.append(", " + field);
			fieldList.append(" ");
			if(processlistView.getBooleanFilter()!=null)
				throw new Exception("Boolean filter not supported");
			StringBuilder whereClause = new StringBuilder();
			for(ListViewFilter lvFilter : processlistView.getFilters())
			{
				if(whereClause.length()!=0)
					whereClause.append("AND ");
				whereClause.append(lvFilter.getField());
				switch (lvFilter.getOperation())
				{
					case equals:
						// TODO: Need to marshal value to correct type based on field type
						whereClause.append(" = " + /* lvFilter.getValue() */ "True" + " "); 
						break;
					case notEqual:
						whereClause.append(" != " + lvFilter.getValue() + " ");
						break;
					case lessThan:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case greaterThan:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case lessOrEqual:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case greaterOrEqual:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case contains:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case notContain:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case startsWith:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case includes:
						throw new Exception("Operator not supported " + lvFilter.getOperation());
					case excludes:			  			
						throw new Exception("Operator not supported " + lvFilter.getOperation());
				}
			}
			// Construct SOQL query statement
			StringBuilder soql = new StringBuilder()
				.append("select ") .append(fieldList.toString())
				.append("from ") .append(sourceObject + " ")
				.append("where ") .append(whereClause.toString());
			
			// Create PushTopic
	        PushTopic pushTopic = new PushTopic();
	        pushTopic.Name = processId;
	        pushTopic.Query = soql.toString();
	        pushTopic.ApiVersion = SALESFORCE_API;
	        pushTopic.NotifyForOperations = "All"; // TODO: Interpret the 'When__c' field from Process__c
	        pushTopic.NotifyForFields = "Referenced"; // TODO: Should this be just the where fields? I think so?       
	        restConnection.create(pushTopic);
	        
	        // Update Process with Push Topic Name (so that if the worker restarts it can reconnect)
	        Process__c process = new Process__c();
	        process.PushTopicName__c = pushTopic.Name;
	        restConnection.update(process, processId);
			
			// Start listening on the new Push Topic immediately!
	        System.out.println("Subscribing for channel: " + pushTopic.Name + " (" + processName + ")");
	        client.getChannel("/topic/"+pushTopic.Name).subscribe(
	        		new SourceObjectListener(loginResult, processId, sourceObject));
	        
	        // Log output
	        logMessage(restConnection, processId, "Push Topic created and subscribed to for " + sourceObject);
		}
		catch (Exception e)
		{
	    	// Log to console
	    	System.err.println(e.getMessage());
	    	try
	    	{
	    		// Attempt to log this in Salesforce
	    		StringBuilder stack = new StringBuilder();
	    		for(StackTraceElement stackTraceElement : e.getStackTrace())
	    			stack.append(stackTraceElement.toString() + "\n");
	    		logMessage(restConnection, processId, "Error: " + e.toString() + "\n" + stack.toString());
	    	}
	    	catch (Exception e2)
	    	{
	    		System.err.println(e.getMessage());
	    	}
		}
		
	}

	/**
	 * Simple POJO for update the Process with the PushTopicName__c
	 */
	public static class Process__c extends com.sforce.rest.pojo.SObject
	{
		public String PushTopicName__c;
		
		public String toString()
		{
			// Workaround, bug in ResConnection.update, unlike RestConnection.create, which calls Gson.toJSon it calls toString?
			return new Gson().toJson(this);
		}
	}
	
	/**
	 * Simple POJO for ProcessStep__c
	 */
	public static class ProcessStep__c extends com.sforce.rest.pojo.SObject
	{
		public String Id;
		public String Name;
		public String HTTPEndPoint__c;
		public String HTTPEndPointFrom__c;
		public String HTTPMethod__c;
		public String HTTPMethodFrom__c;
		public String HTTPHeader__c;
		public String HTTPHeaderFrom__c;
		public String HTTPBody__c;
		public String HTTPBodyFrom__c;		
	}
	
	/**
	 * Simple POJO for ProcessLogLine__c
	 */
	public static class ProcessLogLine__c extends com.sforce.rest.pojo.SObject
	{
		public ProcessLogLine__c(String processId, String message)
		{
			Process__c = processId;
			Message__c = message;
		}
		
		public String Process__c;
		public String Message__c;
	}
	
	/**
	 * Simple POJO for inserting PushTopic records via REST API
	 */
	public static class PushTopic extends com.sforce.rest.pojo.SObject
	{
		public String Name;
		public String Query;
		public String ApiVersion;
		public String NotifyForOperations;
		public String NotifyForFields;
	}
	
	/**
	 * Listener for Process Source Objects
	 * @see processInsert
	 */
	public static class SourceObjectListener implements MessageListener
	{
		private String m_processId;
		private String m_sourceObject;
		private PartnerConnection m_partnerConnection;
		private RestConnection m_restConnection;
		
		public SourceObjectListener(LoginResult loginResult, String processId, String sourceObject)
			throws Exception
		{
			m_processId = processId;
			m_sourceObject = sourceObject;
			
    		// Partner API connection to make queries
    		ConnectorConfig partnerConfig = new ConnectorConfig();        		
    		partnerConfig.setServiceEndpoint(loginResult.getServerUrl());
    		partnerConfig.setSessionId(loginResult.getSessionId());
    		m_partnerConnection = Connector.newConnection(partnerConfig);
    		
    		// Make a REST connection
            m_restConnection = makeRestConnection(loginResult);
		}
		
		@SuppressWarnings("unchecked")
		@Override     
        public void onMessage(ClientSessionChannel channel, Message message) 
        {
            try
            {
            	// Source Object Message
                System.out.println("Received Message: " + message);
        		HashMap<String, Object> data = (HashMap<String, Object>) JSON.parse(message.toString());
        		HashMap<String, Object> record = (HashMap<String, Object>) data.get("data");
        		HashMap<String, Object> sobject = (HashMap<String, Object>) record.get("sobject");
        		String sourceId = (String) sobject.get("Id");
        		
        		// Query Process Steps
                QueryResult processStepsResult = m_partnerConnection.query(
                	"SELECT Id, Name, " + 
        					"HTTPEndPoint__c, HTTPEndPointFrom__c, " +
                			"HTTPMethod__c, HTTPMethodFrom__c, " +
                			"HTTPHeader__c, HTTPHeaderFrom__c, " +
                			"HTTPBody__c, HTTPBodyFrom__c " +
                		"FROM ProcessStep__c " +
                		"WHERE Process__c = '" + m_processId + "'");
                HashSet<String> sourceFields = new HashSet<String>();
                ArrayList<ProcessStep__c> steps = new ArrayList<ProcessStep__c>();                                
                for(SObject step : processStepsResult.getRecords())
                {
                	// HTTP parameters for this step
                	ProcessStep__c processStep = new ProcessStep__c();
                	processStep.Id = (String) step.getField("Id");
                	processStep.Name = (String) step.getField("Name");
                	processStep.HTTPBody__c = (String) step.getField("HTTPBody__c");
                	processStep.HTTPBodyFrom__c = (String) step.getField("HTTPBodyFrom__c");
                	processStep.HTTPEndPoint__c = (String) step.getField("HTTPEndpoint__c"); // TODO: Fix case here
                	processStep.HTTPEndPointFrom__c = (String) step.getField("HTTPEndpointFrom__c"); // TODO: Fix case here
                	processStep.HTTPHeader__c = (String) step.getField("HTTPHeader__c");
                	processStep.HTTPHeaderFrom__c = (String) step.getField("HTTPHeaderFrom__c");
                	processStep.HTTPMethod__c = (String) step.getField("HTTPMethod__c");
                	processStep.HTTPMethodFrom__c = (String) step.getField("HTTPMethodFrom__c");
                	steps.add(processStep);
                	
                	// Values obtained via Source Object fields?
                	if(processStep.HTTPBodyFrom__c.equals("Source Object Field"))
                		sourceFields.add(processStep.HTTPBody__c);
                	// TODO: Check other XXXFrom__c fields
                	// ...
                }                		    		
                
                // Construct Source Object SOQL
        		StringBuilder fieldList = new StringBuilder();
        		fieldList.append("Id, Name");
        		for(String field : sourceFields)
        			fieldList.append(", " + field);    		
        		StringBuilder soql = new StringBuilder()
    				.append("select ")
    				.append(fieldList.toString() + " ")
    				.append("from ")			
    				.append(m_sourceObject + " ")
    				.append("where ")
    				.append("id = '%s'");    
        		String sourceObjectQuery = soql.toString();
                
                // Query Source Object record
        		String query = String.format(sourceObjectQuery, sourceId);
                QueryResult sourceObjectResult = m_partnerConnection.query(query);
                if(sourceObjectResult.getSize()!= 1)
                	throw new Exception("Unable to find source record " + sourceId);
                SObject sourceRecord = sourceObjectResult.getRecords()[0];
                                
                // Log output
                logMessage(m_restConnection, m_processId, "Message received for update to " + m_sourceObject + " : " + sourceRecord.getField("Name") + " (" + sourceId + ")");
                if(steps.size()==0)
                {
                	logMessage(m_restConnection, m_processId, "No steps found to process");
                	return;
                }
                
        		// Create the HTTP client
                HttpClient httpClient = new HttpClient();
                httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);        
                httpClient.start();

                // Execute Process Steps
                for(ProcessStep__c processStep : steps)
                {
                	// HTTP parameters
                	String httpEndpoint = 
                		processStep.HTTPEndPointFrom__c.equals("Literal") ?
                    			processStep.HTTPEndPoint__c : (String) sourceRecord.getField(processStep.HTTPEndPoint__c);
                	String httpMethod = 
                		processStep.HTTPMethodFrom__c.equals("Literal") ?
                			processStep.HTTPMethod__c : (String) sourceRecord.getField(processStep.HTTPMethod__c);
                	String httpBody = 
                		processStep.HTTPBodyFrom__c.equals("Literal") ?
                    			processStep.HTTPBody__c : (String) sourceRecord.getField(processStep.HTTPBody__c);
                	String httpHeader = 
                		processStep.HTTPHeaderFrom__c.equals("Literal") ?
                    			processStep.HTTPHeader__c : (String) sourceRecord.getField(processStep.HTTPHeader__c);                		
                	
                	// TODO: Only POST supported presently, support others
                	if(!httpMethod.equals("POST"))
                		throw new Exception("Only HTTP POST methods supported on Process Steps currently.");
                	
                	// Construct HTTP request
                    ContentExchange contentExchange = new ContentExchange(true);
                    contentExchange.setMethod(httpMethod);
                    contentExchange.setURL(httpEndpoint);
                    String[] headerLines = httpHeader.split("\n"); // TODO: Parsing validation
                    for(String headerLine : headerLines)
                    {
                    	String[] nameValue = headerLine.split(":"); // TODO: Parsing validation
                        contentExchange.setRequestHeader(nameValue[0], nameValue[1]);
                    }
                    contentExchange.setRequestContent(new ByteArrayBuffer(httpBody));
                    System.out.println("Sending Message: \n" + 
                    		httpEndpoint + " " + httpMethod + "\n" + 
                    		httpHeader + "\n" + 
                    		httpBody);
                    httpClient.send(contentExchange);
                    contentExchange.waitForDone();        
                    int responseStatus = contentExchange.getResponseStatus();
                    String responseContent = contentExchange.getResponseContent();
                    System.out.println("Received Response: \n" + 
                    		responseStatus + ": " + responseContent);
                    // TODO: Error handling
                    logMessage(m_restConnection, m_processId, "Step: '" + processStep.Name + "' completed");
                }
                
            }
            catch (Exception e)
            {
            	// Log to console
            	System.err.println(e.getMessage());
            	try
            	{
            		// Attempt to log this in Salesforce
            		StringBuilder stack = new StringBuilder();
            		for(StackTraceElement stackTraceElement : e.getStackTrace())
            			stack.append(stackTraceElement.toString() + "\n");
            		logMessage(m_restConnection, m_processId, "Error: " + e.toString() + "\n" + stack.toString());
            	}
            	catch (Exception e2)
            	{
            		System.err.println(e.getMessage());
            	}
            }
        }		
	}
	
	/**
	 * Creates a RestConnection with the appropirte end point as per LoginResult
	 * @param loginResult
	 * @return
	 * @throws Exception
	 */
	private static RestConnection makeRestConnection(LoginResult loginResult)
		throws Exception
	{
		ConnectorConfig restConfig = new ConnectorConfig();
        URL soapEndpoint = new URL(loginResult.getServerUrl());
        StringBuilder endpointBuilder = new StringBuilder()
            .append(soapEndpoint.getProtocol())
            .append("://")
            .append(soapEndpoint.getHost());
        if (soapEndpoint.getPort() > 0) 
        	endpointBuilder.append(":") .append(soapEndpoint.getPort());
        endpointBuilder.append(REST_ENDPOINT_URI);
		restConfig.setRestEndpoint(endpointBuilder.toString());
		restConfig.setSessionId(loginResult.getSessionId());
		restConfig.setTraceMessage(false);
        return new RestConnectionImpl(restConfig);        
	}
	
	/**
	 * Logs a message for a given Process
	 * @param restConnection
	 * @param processId
	 * @param message
	 * @throws RestApiException
	 * @throws IOException
	 */
	private static void logMessage(RestConnection restConnection, String processId, String message)
		throws RestApiException, IOException 
	{
		System.out.println(processId + " : " + message);
		restConnection.create(new ProcessLogLine__c(processId, message));		
	}
    
	/**
	 * Uses the Jetty HTTP Client and Cometd libraries to connect to Saleforce Streaming API
	 * @param config
	 * @return
	 * @throws Exception
	 */
    private static BayeuxClient makeStreamingAPIConnection(LoginResult loginResult) 
    	throws Exception 
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectTimeout(20 * 1000); // Connection timeout
        httpClient.setTimeout(120 * 1000); // Read timeout
        httpClient.start();

        // Determine the correct URL based on the Service Endpoint given during logon
        URL soapEndpoint = new URL(loginResult.getServerUrl());
        StringBuilder endpointBuilder = new StringBuilder()
            .append(soapEndpoint.getProtocol())
            .append("://")
            .append(soapEndpoint.getHost());
        if (soapEndpoint.getPort() > 0) endpointBuilder.append(":")
            .append(soapEndpoint.getPort());
        String endpoint = endpointBuilder.toString();
        System.out.println("Endpoint: " + endpoint);
        
        // Ensure Session ID / oAuth token is passed in HTTP Header
        final String sessionid = loginResult.getSessionId();
        Map<String, Object> options = new HashMap<String, Object>();
        options.put(ClientTransport.TIMEOUT_OPTION, httpClient.getTimeout());
        LongPollingTransport transport = new LongPollingTransport(options, httpClient) 
        	{
	        	@Override    
	            protected void customize(ContentExchange exchange) 
	        	{
	                super.customize(exchange);
	                exchange.addRequestHeader("Authorization", "OAuth " + sessionid);
	            }
        	};

        // Construct Cometd BayeuxClient
        BayeuxClient client = new BayeuxClient(new URL(endpoint + STREAMING_ENDPOINT_URI).toExternalForm(), transport);
                
        // Add listener for handshaking
        client.getChannel(Channel.META_HANDSHAKE).addListener
            (new ClientSessionChannel.MessageListener() {

            public void onMessage(ClientSessionChannel channel, Message message) {

            	System.out.println("[CHANNEL:META_HANDSHAKE]: " + message);

                boolean success = message.isSuccessful();
                if (!success) {
                    String error = (String) message.get("error");
                    if (error != null) {
                        System.out.println("Error during HANDSHAKE: " + error);
                        System.out.println("Exiting...");
                        System.exit(1);
                    }

                    Exception exception = (Exception) message.get("exception");
                    if (exception != null) {
                        System.out.println("Exception during HANDSHAKE: ");
                        exception.printStackTrace();
                        System.out.println("Exiting...");
                        System.exit(1);

                    }
                }
            }

        });

        // Add listener for connect
        client.getChannel(Channel.META_CONNECT).addListener(
            new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {

                System.out.println("[CHANNEL:META_CONNECT]: " + message);

                boolean success = message.isSuccessful();
                if (!success) {
                    String error = (String) message.get("error");
                    if (error != null) {
                        System.out.println("Error during CONNECT: " + error);
                        System.out.println("Exiting...");
                        System.exit(1);
                    }
                }
            }

        });

        // Add listener for subscribe
        client.getChannel(Channel.META_SUBSCRIBE).addListener(
            new ClientSessionChannel.MessageListener() {

            public void onMessage(ClientSessionChannel channel, Message message) {

            	System.out.println("[CHANNEL:META_SUBSCRIBE]: " + message);
                boolean success = message.isSuccessful();
                if (!success) {
                    String error = (String) message.get("error");
                    if (error != null) {
                        System.out.println("Error during SUBSCRIBE: " + error);
                        System.out.println("Exiting...");
                        System.exit(1);
                    }
                }
            }
        });

        // Begin handshaking
        client.handshake();
        System.out.println("Waiting for handshake");        
        boolean handshaken = client.waitFor(10 * 1000, BayeuxClient.State.CONNECTED);
        if (!handshaken) {
            System.out.println("Failed to handshake: " + client);
            System.exit(1);
        }

        return client;
    }
}