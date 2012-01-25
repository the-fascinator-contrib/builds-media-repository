from au.edu.usq.fascinator.api.storage import StorageException
from au.edu.usq.fascinator.common import JsonSimple
from au.edu.usq.fascinator.api.indexer import SearchRequest
from au.edu.usq.fascinator.common.solr import SolrResult
from java.io import ByteArrayInputStream, ByteArrayOutputStream

import java.lang.Exception as JavaException

class UsqLiveData:
    def __init__(self):
        pass

    def __activate__(self, context):
        # Remember, anything request specific can only
        #   be retrieved from the velocity context.
        self.formData = context["formData"]
        self.roleManager = context["security"].getRoleManager()
        self.page = context["page"]
        self.Services = context["Services"]
        self.response = context["response"]
        
        self.sessionState = context["sessionState"]

        # Retrieve the object from storage
        self.storage = Services.storage
        self.oid = self.formData.get("oid")
        self.__object = None

        # User details
        self.username = None
        self.owner = None

        # Database / logging
        self.__log = context["log"]
        self.__db = Services.database
        self.__dbName = "tfMoodle"
        self.__dbError = False
        self.__dbErrorMsg = ""
        self.__dbInit()
        self.__offline = False
        
        # Initialise
        self.codeList = []
        self.yearList = []
        self.semesterList = []
        self.otherList = []
        
        testOid = self.formData.get("isIndexed")
        if testOid:
            isIndexed = self.__isIndexed(testOid)
            print " *** isIndexed(%s) %s" % (testOid, isIndexed)
            self.__respondWith('{"oid":"%s", "isIndexed":%s}' % (testOid, str(isIndexed).lower()))
            return
    
    def __isIndexed(self, oid):
        query = 'id:"%s"' % oid
        req = SearchRequest(query)
        req.addParam("fq", 'item_type:"object"')
        out = ByteArrayOutputStream()
        self.Services.indexer.search(req, out)
        solrData = SolrResult(ByteArrayInputStream(out.toByteArray()))
        return solrData.getNumFound()!=0

    def __respondWith(self, jsonStr):
        writer = self.response.getPrintWriter("text/plain; charset=UTF-8")
        writer.println(jsonStr)
        writer.close()

    def getMetadata(self):
        metadata = JsonSimple("{}")
        try:
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
        if value is None:
            # use defaults
            if field == "title":
                value = "My Title"
            elif field == "description":
                value = "My Description"
        return value

    def getObject(self):
        # Try and cache the object
        if self.__object is None:
            # Missing form data?
            if self.oid is None:
                return None
            # Go get it from storage, and cache it
            try:
                self.__object = self.storage.getObject(self.oid)
            except StorageException, e:
                self.__log.error("Error retrieving object: '{}'", self.oid, e)
        # Return whatever we have (possibly NULL)
        return self.__object

    def getOwner(self):
        if self.owner is None:
            object = self.getObject()
            if object is None:
                # Object error
                return None
            metadata = object.getMetadata()
            if metadata is None:
                # No metadata?
                return None
            self.owner = metadata.getProperty("owner")
        return self.owner

    def getUser(self):
        if self.username is None:
            self.username = self.page.authentication.get_username()
        return self.username

    def queryMoodleOld(self, username):
        # We are going to bypass the authentication Jython
        #   object to directly access the moodle plugin.
        
        self.roleManager.setActivePlugin("moodle")
        roles = self.roleManager.getRoles(username)

        rolesMap = {}
        # For each role (courseId)
        for role in roles:
            thisRole = JsonSimple()
            # Query the database for the course's name
            name = self.__dbGet(role)
            if name is not None:
                thisRole.getJsonObject().put("name", name)

                # Query the database for the course's Peoplesoft data
                psData = self.__dbGetPS(role)
                if psData is not None:
                    thisRole.getJsonObject().put("psData", psData)
                rolesMap[role] = thisRole
        return rolesMap

    def queryMoodle(self, usernames):
        
        self.roleManager.setActivePlugin("moodle")
        
        rolesMap = {}
        
        courseMap = {}
#        codeList = []
#        yearList = []
#        semesterList = []
#        otherList = []
                
        for username in usernames:
            roles = self.roleManager.getRoles(username)
            for role in roles:
                thisRole = JsonSimple()
                name = self.__dbGet(role)
                if name is not None:
                    thisRole.getJsonObject().put("name", name)
                    
                    psData = self.__dbGetPS(role)
                    if psData is not None:
                        thisRole.getJsonObject().put("psData", psData)
                        code, year, semester = psData.split("_")
                        if code not in self.codeList:
                            self.codeList.append(code)
                        if year not in self.yearList:
                            self.yearList.append(year)
                        if semester not in self.semesterList:
                            self.semesterList.append(semester)
                            
                        courseMap["%s %s %s" % (code, year, semester)] = {"id": role, "name": name, "psData": psData}
                    else:
                        courseName = "%s_%s" % (name, role)
                        if courseName not in self.otherList:
                            self.otherList.append(courseName)
                        courseMap[courseName] = {"id": role, "name": name, "psData": None}
                        #otherList.append({"id": role, "name": name, "psData": None})
                    rolesMap[role] = thisRole
        
        self.codeList.sort()
        self.yearList.sort()
        self.yearList.reverse()
        self.semesterList.sort()
        self.otherList.sort()
        
        return courseMap

###########
# Methods relating to database interaction
###########

    def __dbInit(self):
        # Does the database already exist?
        check = self.__dbCheck()
        if check is None:
            self.__offline = True

    def __dbCheck(self):
        try:
            return self.__db.checkConnection(self.__dbName)
        except JavaException, e:
            msg = self.__dbParseError(e)
            if msg == "Database does not exist":
                # Expected failure
                return None;
            else:
                # Something is wrong
                self.__log.error("Error connecting to database:", e)
                self.__dbError = True
                self.__dbErrorMsg = msg
                return None;

    def __dbGetError(self):
        return self.__dbErrorMsg

    def __dbHasError(self):
        return self.__dbError

    # Strip out java package names from error strings.
    def __dbParseError(self, error):
        self.has_error = True
        message = error.getMessage()
        i = message.find(":")
        if i != -1:
            return message[i+1:].strip()
        else:
            return message.strip()

    def __dbResetErrors(self):
        self.__dbError = False
        self.__dbErrorMsg = ""

    def __dbGet(self, courseId):
        self.__dbResetErrors()
        index = "moodleCourses-SELECT"
        sql = """
SELECT name
FROM   course
WHERE  id = ?
"""
        fields = [courseId]
        try:
            result = self.__db.select(self.__dbName, index, sql, fields)
            # Make sure we got a response
            if result is None or result.isEmpty():
                return None
            return result.get(0).get("NAME")
        except JavaException, e:
            # Something is wrong
            self.__log.error("Error querying database:", e)
            self.__dbError = True
            self.__dbErrorMsg = self.__dbParseError(e)
            return None

    def __dbGetPS(self, courseId):
        self.__dbResetErrors()
        index = "moodleCoursesPS-SELECT"
        sql = """
SELECT *
FROM   ps_course
WHERE  id = ?
"""
        fields = [courseId]
        try:
            result = self.__db.select(self.__dbName, index, sql, fields)
            # Make sure we got a response
            if result is None or result.isEmpty():
                return None
            course   = result.get(0).get("PSCOURSE")
            year     = result.get(0).get("PSYEAR")
            semester = result.get(0).get("PSSEMESTER")
            return course + "_" + year + "_" + semester
        except JavaException, e:
            # Something is wrong
            self.__log.error("Error querying database:", e)
            self.__dbError = True
            self.__dbErrorMsg = self.__dbParseError(e)
            return None
