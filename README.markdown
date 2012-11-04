DF12 Session: Process Orchestration using Streaming API and Heroku
==================================================================

The Streaming API can provide near real time updates on changes to any object data in an organisation. In this session we will demonstrate and share code for a custom Process Orchestration solution powered by the Streaming API and Heroku. Using this solution users can define criteria which the platform will apply to monitor records created or updated by users in real time. The session will demonstrate an example process using REST services taken from those offered by many of todays leading REST API providers including Salesforce.

The slides from this session are [here](http://www.slideshare.net/afawcett/df12-process-orchestration-using-streaming-api-and-heroku?ref=http://andrewfawcett.wordpress.com/).

Overview
--------

This sample consists of two components across two cloud platforms! The Force.com code and the Java code. These can be found in **/forcedotcom** and **/java** subfolders respectively. The Java code is supplied with its own pom.xml file for use with the Maven build tool and thus of course ready to deploy to Heroku. If your not a Java programmer or not familiar with Heroku, but would still like to try this sample out. Do not fear and keep reading!

Heroku Apex API
---------------

This sample also demonstrates how to call the Heroku REST API from Apex if your interested in seeing how that works, take a look at the **SetupController** class. This is very basic and raw way to call the Heroku API, it would be better to mirror something similar to the native wrappers such as the Ruby and Java ones already available in the Heroku Github [repositories](https://github.com/heroku/heroku.jar). 

Sample Installation and Configuration
-------------------------------------

Most of the code is contained in the Java **WorkerProcess** class. The Force.com part is purely providing the storage and front end to the sample. There is a small Apex Trigger on the Process object that cleans up the corresponding PushTopic record, since the Streaming API does not notify on delete.

The following sections give you instructions on deploying, configuring and setting up the demo sample I presented in the Dreamforce 2012 session should you wish. Enjoy!

Installation via Force.com Workbench
------------------------------------

You can install the sample code via the Force.com Workbench and the Heroku Java Application cloning feature. By taking this approach you don't need to understand the various IDE's, build tools and deployment tools the two environments require right now. Here is how...

1. If you don't already have one, setup a **Force.com Developer Edition** [here](http://www.developerforce.com/events/regular/registration.php).
2. If you don't already have one, setup a **Heroku Account** [here](http://www.heroku.com).
3. Extract the sample code using the Zip file download option above.
4. Unzip the contents and immediatly zip the **/forcedotcom/src** folder.
5. Login into the [Force.com Workbench](https://workbench.developerforce.com) using the login from step 1
6. Select **Deploy** from the **Migration** menu and select the Zip file created in step 3 and deploy it.
7. Once deployed, ensure your Force.com Profile can see the Process Manager app and the three tabs, Setup, Processes and Invoices.
8. Select the **Setup tab** from the **Process Manager** application and proceed to the next set of steps.

Configuration of Heroku from a Force.com Visualforce Page
---------------------------------------------------------

Once the Force.com aspect has been deployed you can access the **Setup tab**. This will help you create the Heroku part of this sample. You can if you want deploy it manually if your familiar with the Heroku command line. 

However Heroku Java applications can utilise a feature that allows previously deployed applications (such as the one used in my Dreamforce 2012 session) to be copied into other Heroku accounts just by clicking a link! Cool huh! The Setup tab gives you the option of taking either routes.

1. Select the **Setup tab**. The first time this is run it will automatically create two **PushTopic** records for the **Procces__c** and **ProcessLogLine__c** objects. This allows the Java Worker to automatically create new PushTopic's as new Process records are added. And for the Live Log Viewer to display as log records are inserted. Click the Heroku Setup... button if shown.
2. Complete the steps show on the screen, your **Heroku API** key is found under the Settings link, once logged into Heroku, copy and paste it.
3. When prompted for the **Heroku Application Name** you can either enter the name of one already setup (you deployed yourself) or click the 'Create...' link to clone automatically the one I used in the Dreamforce session into your Heroku environment. If you do this, it will show an error after cloning, as it is assuming a web application. Don't worry about this, it worked fine. Just take a note of the first part of the URL shown. This is the Heroku Application Name you need to enter into the Wizard!
4. Once you have entered the **Salesforce Login** configuration information. The wizard will attempt to start the worker contained in the application on Heroku. While its a bit raw, you should be able to observe the status of the process as you hit the Refresh button on the page. Once it's status is showing as 'up' and stays that way, your good to go! You can revisit this page at any time to check its status.

**NOTE:** I have an iPhone application called Doppler, this allows me to see the logs and monitor the status of my Heroku applications. Its free as well! I'll hopefully get some time to enhance the Setup tab above to do this in the near future.

Setting up the Invoicing Demo with the eBay API
-----------------------------------------------

The demonstration and defaults for the fields on the Process and Process Step objects are tailored towards a use case featuring the eBay Seller API. If you want to explore this further and repeat the demonstration on your own, here is how...

1. Obtain a **eBay Sandbox** by reading this  [page](https://www.x.com/developers/ebay/documentation-tools/quickstartguide) and setting up a X Developer account.
2. Obtain your **eBay Sandbox [Token](https://developer.ebay.com/DevZone/account/tokens/default.aspx)** and paste it into the Setup page and click Save. The formula field on the Invoice object that constructs the body of the eBay API call **CompleteSale** uses the custom setting fields this page updated to include the token in the API call.
3. Create seller and buyer sandbox users and [login](https://signin.sandbox.ebay.com/), list and purchase an item, noting the eBay item ID.
4. Create an Invoice record and ensure to paste in the eBay item ID.
5. Create a **Process** and **Process Step**. All of the fields are defaulted to support this sample use case, you just need to enter some names for the Process and Process Step, which don't really matter.
6. You should see the **Live Log Viewer** update when you create the Process. If not refresh the screen manually and check the log entries are shown in the log related list. If nothing happens check the status of your Heroku worker via the Setup tab.
7. Update your Invoice dispatch status and keep an eye on the Process Live Log Viewer.

You should see any errors come back through the process log, if not, check the Heroku log via the Heroku command line tool or the Doppler iPhone application.

Finally, this should not be that tricky to setup, eBay have done a good job at providing a good set of docs and environments. It took me a couple of hours from scratch to get my head round it. That said, some times things don't go to plan, if you need a quick tip or pointing in the best direction just raise an issue via Github (tab above) and I'll do my best to help out!