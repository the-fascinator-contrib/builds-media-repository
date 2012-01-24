import re

from java.net import URLDecoder
from au.edu.usq.fascinator.common import JsonSimple

class MetadataData:
    def __init__(self):
        pass

    def __activate__(self, context):
        self.request = context["request"]
        self.services = context["Services"]
        
        self.__metadata = JsonSimple()
        
        # get the oid
        uri = URLDecoder.decode(self.request.getAttribute("RequestURI"))
        matches = re.match("^(.*?)/(.*?)/(?:(.*?)/)?(.*)$", uri)
        if matches and matches.group(3):
            oid = matches.group(3)
            
        self.__object = self.services.getStorage().getObject(oid)
        
        self.__mergeData()
        
        response = context["response"]
        response.setHeader("Content-Disposition", "attachment; filename=metadata.json")
        writer = response.getPrintWriter("application/json; charset=UTF-8")
        #Content-Disposition
        writer.println(self.__metadata)
        writer.close()
        
    def __mergeData(self):
        
        requiredMetadata = ["format", "width", "height", "timeSpent", "size"]
        
        try:
            # ffmpeg.info data:
            ffmpegPayload = self.__object.getPayload("ffmpeg.info")
            ffmpegInfo = JsonSimple(ffmpegPayload.open())
            
            ffmpegOutput = ffmpegInfo.getJsonSimpleMap(["outputs"])
            
            map = JsonSimple()
            for key in ffmpegOutput.keySet():
                data = ffmpegOutput.get(key)
                detailMap = JsonSimple()
                for field in data.getJsonObject().keySet():
                    if field in requiredMetadata:
                        detailMap.getJsonObject().put(field, data.getString("", [field]))
                map.getJsonObject().put(key, detailMap)
            
            self.__metadata.getJsonObject().put("outputs", map)
            ffmpegPayload.close()
        except Exception, e:
            print str(e)
        
        try:
            # workflow.metadata
            workflowPayload = self.__object.getPayload("workflow.metadata")
            workflowInfo = JsonSimple(workflowPayload.open())
            
            formData = workflowInfo.getObject(["formData"])
            for key in formData.keySet():
                self.__metadata.getJsonObject().put(key, formData.get(key))
            workflowPayload.close()
        except Exception, e:
            print str(e)
        

    def getJson(self):
        return self.__metadata