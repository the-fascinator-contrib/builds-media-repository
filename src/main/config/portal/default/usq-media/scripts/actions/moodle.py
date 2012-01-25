from au.edu.usq.fascinator.common import JsonSimple

import java.lang.Exception as JavaException

class MoodleData:

    def __init__(self):
        pass

    def __activate__(self, context):
        self.velocityContext = context
        self.roleManager = context["security"].getRoleManager()

        # Database / logging
        self.__log = context["log"]
        self.__db = Services.database
        self.__dbName = "tfMoodle"
        self.__dbError = False
        self.__dbErrorMsg = ""
        self.__dbInit()
        self.__offline = False

        # Go Go Gadget AJAX
        self.writer = self.vc("response").getPrintWriter("text/plain; charset=UTF-8")
        self.process()

    # Get from velocity context
    def vc(self, index):
        if self.velocityContext[index] is not None:
            return self.velocityContext[index]
        else:
            log.error("ERROR: Requested context entry '" + index + "' doesn't exist")
            return None

    def process(self):
        action = self.vc("formData").get("verb")

        switch = {
            "getCourseName" : self.getCourseName,
            "queryMoodle"   : self.queryMoodle
        }
        switch.get(action, self.unknown_action)()

    def getCourseName(self):
        course = self.vc("formData").get("courseId")
        name = self.__dbGet(course)
        if name is None:
            name = "Unknown"
        psData = self.__dbGetPS(course)
        if psData is None:
            psData = ""
        response = '{"name": "' + name + '", "psData": "' + psData + '"}'
        self.writer.println(response)
        self.writer.close()

    def queryMoodle(self):
        username = self.vc("formData").get("username")
        # We are going to bypass the authentication Jython
        #   object to directly access the moodle plugin.
        self.roleManager.setActivePlugin("moodle")
        roles = self.roleManager.getRoles(username)
        if len(roles) == 0:
            self.throw_error("The given user is invalid or has no courses.")
        else:
            roleIds = []
            roleList = []
            # For each role (courseId)
            for role in roles:
                thisRole = JsonSimple()
                # Query the database for the course's name
                name = self.__dbGet(role)
                if name is not None:
                    roleIds.append("\"" + role + "\"")
                    thisRole.getJsonObject().put("name", name)
                    psData = self.__dbGetPS(role)
                    if psData is not None:
                        thisRole.getJsonObject().put("psData", psData)
                    roleList.append("\"" + role + "\" : " + thisRole.toString())
            keys = "\"keys\" : [" + ",".join(roleIds) + "]"
            data = "\"data\" : {" + ",".join(roleList) + "}"
            response = "{" + keys + "," + data + "}"
            self.writer.println(response)
            self.writer.close()

    def throw_error(self, message):
        self.vc("response").setStatus(500)
        self.writer.println("Error: " + message)
        self.writer.close()

    def unknown_action(self):
        self.throw_error("Unknown action requested - '" + self.vc("formData").get("verb") + "'")

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
