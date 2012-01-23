class DirectPending:
    def __init__(self):
        pass

    def getFormData(self, field):
        value = formData.get(field)
        if value is None:
            return ""
        else:
            return value
    
scriptObject = DirectPending()
