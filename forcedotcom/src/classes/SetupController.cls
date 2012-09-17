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

/**
 * This controller uses the Heroku REST API to setup and configure a Heroku Work Process for this sample
 **/
public with sharing class SetupController 
{
	// Wizard steps
	public boolean ShowStep1 {get; set;}
	public boolean ShowStep2 {get; set;}
	public boolean ShowStep3 {get; set;}
	public boolean ShowStep4 {get; set;}	
	public boolean CompletedStep1 {get; set;}
	public boolean CompletedStep2 {get; set;}
	public boolean CompletedStep3 {get; set;}

	// Wizard state
	public String APIKey {get; set;}
	public String ApplicationName {get; set;}
	public String UserName {get; set;}
	public String Password {get; set;}	
	public String Token {get; set;}	
	public String WorkerStatus {get; set;}
	public String EbayAPIToken {get; set;}
	
	public SetupController()
	{
		// Wizard steps
		ShowStep1 = false;	
		ShowStep2 = false;	
		ShowStep3 = false;
		ShowStep4 = false;		
		CompletedStep1 = false;
		CompletedStep2 = false;
		CompletedStep3 = false;
	}
	
	/**
	 * Check the existing configuration is still OK and the worker process is in an up state
	 **/
	public PageReference init()
	{
		try
		{
			// Default PushTopic's already created?
			List<PushTopic> existingPushTopics = [select Id, Name from PushTopic];
			Set<String> existingPushTopicNames = new Set<String>();
			for(PushTopic pushTopic : existingPushTopics)
				existingPushTopicNames.add(pushTopic.name);
			List<PushTopic> newPushTopics = new List<PushTopic>();
			if(!existingPushTopicNames.contains('processchanges'))
			{
				PushTopic topic = new PushTopic();
				topic.Name = 'processchanges';
				topic.Query = 'SELECT Id, Name, ListViewName__c, SourceObject__c, When__c FROM Process__c';
				topic.ApiVersion = 25.0;
				topic.NotifyForOperations = 'All';
				topic.NotifyForFields = 'Referenced';
				newPushTopics.add(topic);
			}
			if(!existingPushTopicNames.contains('processchanges'))
			{
				PushTopic topic = new PushTopic();
				topic.Name = 'processloglinescreated';
				topic.Query = 'SELECT Id, Name, Process__c, Message__c FROM ProcessLogLine__c';
				topic.ApiVersion = 25.0;
				topic.NotifyForOperations = 'All';
				topic.NotifyForFields = 'Referenced';
				newPushTopics.add(topic);
			}
			if(newPushTopics.size()>0)
			{
				insert newPushTopics;
				return null;
			}
			
			// Show first step
			ShowStep1 = true;
			
			// Retrieve config
			HerokuConfig__c config = HerokuConfig__c.getInstance();
			APIKey = config.APIKey__c;
			ApplicationName = config.AppName__c;

			// Partial setup?
			if(APIKey==null)
				return null;
			
        	// Make Heroku API call			
			makeRequest('https://api.heroku.com/apps', 'GET', null);
        	CompletedStep1 = true;
        	ShowStep2 = true;		

			// Partial setup?
			if(ApplicationName==null)
				return null;
			
        	// Make Heroku API call	to get the app		
			makeRequest('https://api.heroku.com/apps/'+ApplicationName, 'GET', null);
        	CompletedStep2 = true;
        	ShowStep3 = true;		
        	
        	// Make Heroku API call to confirm configuration 
        	String configResponse = makeRequest('https://api.heroku.com/apps/'+ApplicationName+'/config_vars', 'GET', null);
			Map<String, String> params = (Map<String,String>) JSON.deserialize(configResponse, Map<String, String>.class);
			if(params.containsKey('SFUSER') && params.containsKey('SFPASSWORD'))
			{        	
				// Already configured	
				CompletedStep3 = true;
				ShowStep4 = true;
			
				// Does a process exist?
				String psResponse = makeRequest('https://api.heroku.com/apps/'+ApplicationName+'/ps', 'GET', null);
				if(psResponse == '[]')
				{
					// Create one
					makeRequest('https://api.heroku.com/apps/'+ApplicationName+'/ps/scale', 'POST', 'type='+EncodingUtil.urlEncode('worker', 'UTF-8')+'&qty='+EncodingUtil.urlEncode('1', 'UTF-8'));					
					// Display its status
					psResponse = makeRequest('https://api.heroku.com/apps/'+ApplicationName+'/ps', 'GET', null);
				}
				
				// Display Workder status (user can refresh page to determine current status)
				WorkerStatus = psResponse;
			}
			
			// Read EbayAPIToken chunks
			eBayAPI__c ebayAPI = eBayAPI__c.getInstance();
			EbayAPIToken = '';
			if(ebayAPI.UserTokenPart1__c!=null)
				EbayAPIToken += ebayAPI.UserTokenPart1__c;
			if(ebayAPI.UserTokenPart2__c!=null)
				EbayAPIToken += ebayAPI.UserTokenPart2__c;
			if(ebayAPI.UserTokenPart3__c!=null)
				EbayAPIToken += ebayAPI.UserTokenPart3__c;
			if(ebayAPI.UserTokenPart4__c!=null)
				EbayAPIToken += ebayAPI.UserTokenPart4__c;			
        } 
        catch(Exception e) 
        {
        	ApexPages.addMessages(e);
        }
		return null;
	}
	
	public PageReference saveAPIKey()
	{
        try 
        {
        	// Make Heroku API call to test key
        	makeRequest('https://api.heroku.com/apps', 'GET', null);
        	
        	// Store API key in a custom setting
        	HerokuConfig__c config = HerokuConfig__c.getInstance();
        	config.APIKey__c = APIKey;
        	upsert config;
        	
        	// Next step
        	CompletedStep1 = true;
        	ShowStep2 = true;
        } 
        catch(Exception e) 
        {
        	ApexPages.addMessages(e);
        }
		return null;
	}
	
	public PageReference saveAppName()
	{
        try 
        {
        	// Make Heroku API call to validate applicaiton name
        	makeRequest('https://api.heroku.com/apps/'+ApplicationName, 'GET', null);
        	
        	// Store API key in a custom setting
        	HerokuConfig__c config = HerokuConfig__c.getInstance();
        	config.AppName__c = ApplicationName;
        	upsert config;
        	
        	// Next step
        	CompletedStep2 = true;
        	ShowStep3 = true;
        } 
        catch(Exception e) 
        {
        	ApexPages.addMessages(e);
        }
		return null;
	}
	
	public PageReference saveWorkerConfig()
	{
        try 
        {
        	// Make Heroku API call to set configuration allow it to subscribe to this org
			String body = 
				'{' + 
				  '"SFUSER": "' + UserName + '",' +
				  '"SFPASSWORD": "' + Password+Token + '"' +
				'}';
			makeRequest('https://api.heroku.com/apps/' + ApplicationName + '/config_vars', 'PUT', body);
			        	
			// Restart page
			return init();			
        }		
        catch(Exception e) 
        {
        	ApexPages.addMessages(e);
        }
		return null;
	}
	
	public PageReference resetWorkerConfig()
	{
		// Allow user to re-enter worker process config
		CompletedStep3 = false;
		return null;
	}
	
	public PageReference restartWorker()
	{
        try 
        {
        	// Make Heroku API call to restart the worker process		
			makeRequest('https://api.heroku.com/apps/' + ApplicationName + '/ps/restart', 'POST', null);
			        	
			// Restart page
			return init();						
        }
        catch(Exception e) 
        {
        	ApexPages.addMessages(e);
        }
		return null;
	}
		
	public PageReference saveEbayAPIToken()
	{
		// Custom setting fields have a max of 255, so we need to split this api token up
		eBayAPI__c ebayAPI = eBayAPI__c.getInstance();
		List<String> chunks = splitIntoChunks(EbayAPIToken, 255);
		ebayAPI.UserTokenPart1__c = null;
		ebayAPI.UserTokenPart2__c = null;
		ebayAPI.UserTokenPart3__c = null;
		ebayAPI.UserTokenPart4__c = null;	
		if(chunks.size()>0)
			ebayAPI.UserTokenPart1__c = chunks[0];					
		if(chunks.size()>1)
			ebayAPI.UserTokenPart2__c = chunks[1];					
		if(chunks.size()>2)
			ebayAPI.UserTokenPart3__c = chunks[2];					
		if(chunks.size()>3)
			ebayAPI.UserTokenPart4__c = chunks[3];					
		upsert ebayAPI;
		return null;
	}	

	private List<String> splitIntoChunks(String text, integer chunkSize)
	{
		// Util code to split up large strings
	    List<String> chunks = new List<String>();
	    integer offset = 0;
	    while (offset < text.length())
	    {
	        integer partSize = Math.min(chunkSize, text.length() - offset);
	        chunks.add(text.substring(offset, offset + partSize));
	        offset += partSize;
	    }
	    return chunks;
	}		
		
	private String makeRequest(String url, String method, String body)
	{
		// Makes a REST call to the Heroku API
		HttpRequest req = new HttpRequest();
        HttpResponse res = new HttpResponse();
        Http http = new Http();
        req.setHeader('Accept', 'application/json');
 		req.setHeader('Authorization', 'BASIC ' + EncodingUtil.base64Encode(Blob.valueOf( ':' + APIKey)));
        req.setEndpoint(url);
        req.setMethod(method);
        if(body!=null)
        	req.setBody(body);
        res = http.send(req);
        if(res.getStatusCode() != 200)
        	throw new ControllerException(res.toString() + ' ' + res.getBody());
        return res.getBody();
	}
	
	private class ControllerException extends Exception
	{
		
	}
}