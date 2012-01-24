from au.edu.usq.fascinator.api.storage import StorageException
from au.edu.usq.fascinator.common import JsonSimple
from au.edu.usq.fascinator.portal import FormData
from au.edu.usq.fascinator.api.indexer import SearchRequest
from au.edu.usq.fascinator.common.solr import SolrDoc, SolrResult
from java.io import ByteArrayInputStream, ByteArrayOutputStream

from java.io import ByteArrayInputStream
from java.lang import String
from java.net import URLDecoder

import locale
import re
import time


class WorkflowData:
    def __init__(self):
        pass

    def __activate__(self, context):
        " **** workflow.py... activate"
        self.velocityContext = context
        response = context["response"]
        self.page = context["page"]
        self.request = context["request"]
        self.Services = context["Services"]
        # Test if some actual form data is available
        self.formData = self.vc("formData")
        self.fileName = self.formData.get("upload-file-file")
        self.localFormData = None
        self.metadata = None
        self.object = None
        self.pageService = self.Services.getPageService()
        self.renderer = self.vc("toolkit").getDisplayComponent(self.pageService)
        self.template = None
        self.errorMsg = None
        self.hasUpload = False
        self.fileDetails = None
        self.uploadRequest = None
        self.formProcess = None
        self.fileProcessing = False
        func = self.formData.get("func")
        oid = self.formData.get("oid")
        if oid is None:
            oid = self.formData.get("isIndexed")
        
        # Normal workflow progressions
        print "  func='%s', oid='%s'" % (func, oid)
        if self.fileName is None:
            uploadFormData = self.vc("sessionState").get("uploadFormData")
            if uploadFormData:
                self.fileProcessing = uploadFormData.get("fileProcessing")
            if oid is None and uploadFormData:
                oid = uploadFormData.get("oid")
            if oid is None:
                #for normal workflow progression
                self.formProcess = False
            else:
                self.formProcess = True
        # First stage, post-upload
        else:
            # Some browsers won't match what came through dispatch, resolve that
            dispatchFileName = self.vc("sessionState").get("fileName")
            if dispatchFileName and \
                    dispatchFileName!=self.fileName and \
                    self.fileName.find(dispatchFileName)!=-1:
                self.fileName = dispatchFileName
            self.hasUpload = True
            self.fileDetails = self.vc("sessionState").get(self.fileName)
            print "***** Upload details:", repr(self.fileDetails)
            self.template = self.fileDetails.get("template")
            self.errorMsg = self.fileDetails.get("error")
            #When successfully uploaded, index first to get the workflow.metadata created
            self.Services.indexer.index(self.getOid())
            self.Services.indexer.commit()
        func=self.vc("formData").get("func")

        self.getObject()
        self.getWorkflowMetadata()
        
        if self.formProcess:
            self.processForm()
        
        if func=="upload-direct":
            print "  fileName='%s'" % self.fileName
            dispatchFileName = self.vc("sessionState").get("fileName")
            print "  dispatchFileName='%s'" % dispatchFileName
            print "  object.getId='%s'" % self.getOid()
            print "  wfMeta='%s'" % self.metadata
            print "  fileProcessing=%s" % self.formData.get("fileProcessing")
            self.vc("sessionState").remove("uploadFormData")
            self.processForm()
            print "========================="
            redirect = "workflow/%s" % oid
            writer = response.getPrintWriter("text/plain; charset=UTF-8")
            writer.println('{"ok":"uploaded ok", "oid": "'+self.getOid()+'"}')
            writer.close()
            return

        if func=="upload" or self.formData.get("fileProcessing"):
            dispatchFileName = self.vc("sessionState").get("fileName")
            self.vc("sessionState").remove("uploadFormData")
            self.processForm()
            
            writer = response.getPrintWriter("text/plain; charset=UTF-8")
            writer.println('{"ok":"uploaded ok", "oid": "'+self.getOid()+'"}')
            writer.close()
        #print "workflow.py - UploadedData.__init__() done."
        
        if self.formData.get("isIndexed") and func!= "upload-direct":
            writer = response.getPrintWriter("text/plain; charset=UTF-8")
            writer.println(('{"oid":"%s", "isIndexed":%s}' % (self.formData.get("isIndexed"), "true")))
            writer.close()

    
    # Get from velocity context
    def vc(self, index):
        if self.velocityContext[index] is not None:
            return self.velocityContext[index]
        else:
            log.error("ERROR: Requested context entry '" + index + "' doesn't exist")
            return None

    def getError(self):
        if self.errorMsg is None:
            return ""
        else:
            return self.errorMsg

    def getFileName(self):
        if self.uploadDetails() is None:
            return ""
        else:
            return self.uploadDetails().get("name")

    def getFileSize(self):
        if self.uploadDetails() is None:
            return "0kb"
        else:
            size = float(self.uploadDetails().get("size"))
            if size is not None:
                size = size / 1024.0
            locale.setlocale(locale.LC_ALL, "")
            return locale.format("%.*f", (1, size), True) + " kb"

    def getObjectMetadata(self):
        if self.getObject() is not None:
            try:
                return self.object.getMetadata()
            except StorageException, e:
                pass
        return None

    def getWorkflowMetadata(self):
        if self.metadata is None:
            if self.getObject() is not None:
                try:
                    wfPayload = self.object.getPayload("workflow.metadata")
                    self.metadata = JsonSimple(wfPayload.open())
                    wfPayload.close()
                except StorageException, e:
                    pass
        return self.metadata

    def getOid(self):
        if self.getObject() is None:
            return None
        else:
            return self.getObject().getId()

    def getObject(self):
        if self.object is None:
            # Find the OID for the object
            if self.justUploaded():
                # 1) Uploaded files
                oid = self.fileDetails.get("oid")
            else:
                # 2) or POST process from workflow change
                oid = self.vc("formData").get("oid")
                # 3) Get from uploadFormData
                if oid is None:
                    uploadFormData = self.vc("sessionState").get("uploadFormData")
                    if uploadFormData:
                        oid = uploadFormData.get("oid")
                if oid is None:
                    # 4) or GET on page to start the process
                    uri = URLDecoder.decode(self.vc("request").getAttribute("RequestURI"))
                    basePath = self.vc("portalId") + "/" + self.vc("pageName")
                    oid = uri[len(basePath)+1:]
                
            # Now get the object
            if oid is not None:
                try:
                    self.object = self.Services.storage.getObject(oid)
                    return self.object
                except StorageException, e:
                    self.errorMsg = "Failed to retrieve object : " + e.getMessage()
                    return None
        else:
            return self.object

    def getWorkflow(self):
        return self.fileDetails.get("workflow")

    def hasError(self):
        if self.errorMsg is None:
            return False
        else:
            return True

    def isPending(self):
        metaProps = self.getObject().getMetadata()
        status = metaProps.get("render-pending")
        if status is None or status == "false":
            return False
        else:
            return True

    def justUploaded(self):
        return self.hasUpload

    
    def prepareTemplate(self):
        # Retrieve our workflow config
        try:
            objMeta = self.getObjectMetadata()
            jsonObject = self.Services.storage.getObject(objMeta.get("jsonConfigOid"))
            jsonPayload = jsonObject.getPayload(jsonObject.getSourceId())
            config = JsonSimple(jsonPayload.open())
            jsonPayload.close()
        except Exception, e:
            self.errorMsg = "Error retrieving workflow configuration"
            return False

        # Current workflow status
        meta = self.getWorkflowMetadata()
        if meta is None:
            self.errorMsg = "Error retrieving workflow metadata"
            return False
        currentStep = meta.getString(None, ["step"]) # Names
        nextStep = ""
        currentStage = None # Objects
        nextStage = None

        # Find next workflow stage
        stages = config.getJsonSimpleList(["stages"])
        if stages.size() == 0:
            self.errorMsg = "Invalid workflow configuration"
            return False

        #print "========="
        #print "meta='%s'" % meta        # "workflow.metadata"
        #print "currentStep='%s'" % currentStep
        #print "stages='%s'" % stages
        
        nextFlag = False
        for stage in stages:
            # We've found the next stage
            if nextFlag:
                nextFlag = False
                nextStage = stage
            # We've found the current stage
            if stage.getString(None, ["name"]) == currentStep:
                nextFlag = True
                currentStage = stage

        #print "currentStage='%s'" % currentStage
        #print "nextStage='%s'" % nextStage
        #print "========="

        if nextStage is None:
            if currentStage is None:
                self.errorMsg = "Error detecting next workflow stage"
                return False
            else:
                nextStage = currentStage
        nextStep = nextStage.getString(None, ["name"])

        # Security check
        workflow_security = currentStage.getStringList(["security"])
        user_roles = self.vc("page").authentication.get_roles_list()        ##
        valid = False
        for role in user_roles:
            if role in workflow_security:
                valid = True
        if not valid:
            isLoggedIn = self.page.authentication.is_logged_in()
            self.errorMsg = "Sorry, but your current security permissions don't allow you to administer this item"
            if isLoggedIn==False:
                self.errorMsg += """<p>Would you like to <a href='#' class='login-now'>login now</a>?</p>
                                    <script type='text/javascript'>
                                    $(function(){
                                      $(".login-now:first").click();
                                    });
                                    </script>"""
            return False

        self.localFormData = FormData()     # localFormData for organiser.vm
#        try:
#            autoComplete = currentStage.get("auto-complete", "")
#            self.localFormData.set("auto-complete", autoComplete)
#        except: pass

        # Check for existing data
        oldJson = meta.getObject(["formData"])
        if oldJson is not None:
            for field in oldJson.keySet():
                self.localFormData.set(field, oldJson.get(field))

        # Get data ready for progression
        self.localFormData.set("oid", self.getOid())
        self.localFormData.set("currentStep", currentStep)
        if currentStep == "pending":
            self.localFormData.set("currentStepLabel", "Pending")
        else:
            self.localFormData.set("currentStepLabel", currentStage.getString(None, ["label"]))
        self.localFormData.set("nextStep", nextStep)
        self.localFormData.set("nextStepLabel", nextStage.getString(None, ["label"]))
        self.template = nextStage.getString(None, ["template"])
        print " **** done prepareTemplate **** "
        return True

    
    def processForm(self):
        # Get our metadata payload
        meta = self.getWorkflowMetadata()
        if meta is None:
            self.errorMsg = "Error retrieving workflow metadata"
            return
        # From the payload get any old form data
        oldFormData = meta.getObject(["formData"])
        if oldFormData is not None:
            oldFormData = JsonSimple(oldFormData)
        else:
            oldFormData = JsonSimple()

        # Process all the new fields submitted
        self.processFormData(meta, oldFormData, self.formData)
        self.processFormData(meta, oldFormData, self.vc("sessionState").get("uploadFormData"))
        self.vc("sessionState").remove("uploadFormData")
        
        # Write the form data back into the workflow metadata
        data = oldFormData.getJsonObject()
        metaObject = meta.writeObject(["formData"])
        for field in data.keySet():
            metaObject.put(field, data.get(field))

        # Write the workflow metadata back into the payload
        response = self.setWorkflowMetadata(meta)
        if not response:
            self.errorMsg = "Error saving workflow metadata"
            return

        # Re-index the object
        self.Services.indexer.index(self.getOid())
        self.Services.indexer.commit()


    def processFormData(self, meta, oldFormData, formData):
        if formData is not None:
            # Quick filter, we may or may not use these fields
            #    below, but they are not metadata
            #using the widget from
            jsonForm = formData.get("json")
            if jsonForm:
                formData = JsonSimple(jsonForm)
            specialFields = ["oid", "targetStep", "step", "func"]
            
            #Quick fix to remove the empty keyword/subject from form
            fields = oldFormData.getJsonObject().keySet()
            toBeRemoved = []
            for field in fields:
                if field.startswith("dc_subject"):
                    toBeRemoved.append(field)
            for field in toBeRemoved:
                oldFormData.getJsonObject().remove(field)
            
            if type(formData)==JsonSimple:
                newFormFields = formData.getJsonObject().keySet()
                for field in newFormFields:
                    value = formData.getString("", [field])
                    if field in specialFields:
                        ##print " *** Special Field : '" + field + "' => '" + repr(value) + "'"
                        if field == "targetStep":
                            meta.getJsonObject().put(field, value)
                    else:
                        ##print " *** Metadata Field : '" + field + "' => '" + repr(value) + "'"
                        oldFormData.getJsonObject().put(field, value)
            else:
                newFormFields = formData.getFormFields()
                for field in newFormFields:
                    value = True
                    if value:
                      value = formData.get(field)
                      #print " ** value: ", value
                      # Special fields - we are expecting them
                      if field in specialFields:
                        ##print " *** Special Field : '" + field + "' => '" + repr(value) + "'"
                        if field == "targetStep":
                            meta.getJsonObject().put(field, value)    
                      # Everything else... metadata
                      else:
                        ##print " *** Metadata Field : '" + field + "' => '" + repr(value) + "'"
                        oldFormData.getJsonObject().put(field, value)
    
    def redirectNeeded(self):
        redirect = self.formProcess
        if redirect:
            if self.fileProcessing == "true":
                redirect = False
        return redirect

    def renderTemplate(self):
        r = self.renderer.renderTemplate(self.vc("portalId"), self.template, self.localFormData, self.vc("sessionState"))
        return r

    def setWorkflowMetadata(self, oldMetadata):
        try:
            jsonString = String(oldMetadata.toString())
            inStream = ByteArrayInputStream(jsonString.getBytes("UTF-8"))
            self.object.updatePayload("workflow.metadata", inStream)
            return True
        except StorageException, e:
            return False

    def uploadDetails(self):
        return self.fileDetails

    ###########################        
    def getAllowedRoles(self):
        roles = []
        try:
            oid = self.getOid()
            query = 'id:"%s"' % oid
            req = SearchRequest(query)
            req.addParam("fq", 'item_type:"object"')
            out = ByteArrayOutputStream()
            self.Services.getIndexer().search(req, out)
            solrData = SolrResult(ByteArrayInputStream(out.toByteArray()))
            metadata = solrData.getResults().get(0)
            if solrData.getNumFound()==1:
                roles = metadata.getList("security_filter")
        except Exception, e:
            print " Error in getAllowedRoles() - '%s'" % str(e)
        return roles


    def isAccessDenied(self):
        myRoles = self.page.authentication.get_roles_list()
        allowedRoles = self.getAllowedRoles()
        if myRoles is None or allowedRoles is None:
            return True
        for role in myRoles:
            if role in allowedRoles:
                return False
        return True
