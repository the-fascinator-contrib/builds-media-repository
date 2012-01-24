from au.edu.usq.fascinator.api.storage import StorageException
from au.edu.usq.fascinator.common import JsonSimple

import java.lang.Exception as JavaException

class DirectInputData:
    def __init__(self):
        pass

    def __activate__(self, context):
        # Remember, anything request specific can only
        #   be retrieved from the velocity context.
        self.log = context["log"]
        self.page = context["page"]
        self.Services = context["Services"]
        self.roleManager = context["security"].getRoleManager()
        self.sessionState = context["sessionState"]
        self.formData = context["formData"]
        self.object = None
        self.storage = self.Services.storage
        # Retrieve the object from storage
        self.oid = self.formData.get("oid")
        self.username = self.page.authentication.get_username()

    
    def getMetadata(self):
        metadata = JsonSimple("{}")
        try:
            #metadata = object.getMetadata()
            #self.owner = metadata.getProperty("owner")
            obj = self.getObject()
            if obj:
                payload = obj.getPayload("workflow.metadata")
                metadata = JsonSimple(payload.open())
                payload.close()
        except JavaException, e:
            print "Failed to read workflow metadata!", e.getMessage()
        return metadata


    def getFormData(self, field):
        # check formData
        value = self.formData.get(field)
        if value is None:
            # try from workflow metadata
            formData = self.getMetadata().getObject("formData")
            if formData:
                value = formData.get(field)
        return value


    def getObject(self):
        # Try and cache the object
        if self.object is None:
            # Missing form data?
            if self.oid is None:
                return None
            # Go get it from storage, and cache it
            try:
                self.object = self.storage.getObject(self.oid)
            except StorageException, e:
                self.log.error("Error retrieving object: '{}'", self.oid, e)
        # Return whatever we have (possibly NULL)
        return self.object

