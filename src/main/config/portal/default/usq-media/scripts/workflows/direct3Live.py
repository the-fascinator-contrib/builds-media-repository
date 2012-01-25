

class Direct3LiveData:
    def __init__(self):
        pass

    def __activate__(self, context):
        self.velocityContext = context
        self.formData = self.vc("formData")


    # Get from velocity context
    def vc(self, index):
        if self.velocityContext[index] is not None:
            return self.velocityContext[index]
        else:
            log.error("ERROR: Requested context entry '" + index + "' doesn't exist")
            return None


    def getFormData(self, field):
        value = self.formData.get(field)
        if value is None:
            return ""
        else:
            return value
    


