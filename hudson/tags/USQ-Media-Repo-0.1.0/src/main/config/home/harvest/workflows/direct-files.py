import time

from au.edu.usq.fascinator.api.storage import StorageException
from au.edu.usq.fascinator.common import JsonConfigHelper
from au.edu.usq.fascinator.common.storage import StorageUtils

from java.io import ByteArrayInputStream
from java.lang import String

class IndexData:
    def __init__(self):
        pass

    def __activate__(self, context):
        # Prepare variables
        self.index = context["fields"]
        self.object = context["object"]
        self.payload = context["payload"]
        self.params = context["params"]
        self.utils = context["pyUtils"]
        self.config = context["jsonConfig"]

        self.wfSecurityExceptions = None
        self.message_list = None

        # Because the workflow messaging system wants access to this data
        #  BEFORE it actual hits the index we are going to cache it into an
        #  object payload too.
        self.directIndex = JsonConfigHelper()

        # Common data
        self.__newDoc()

        # Real metadata
        if self.itemType == "object":
            self.__previews()
            self.__basicData()
            self.__metadata()
            # Update the 'direct.index' payload - BEFORE messages are sent
            directString = String(self.directIndex.toString())
            inStream = ByteArrayInputStream(directString.getBytes("UTF-8"))
            try:
                StorageUtils.createOrUpdatePayload(self.object, "direct.index", inStream)
            except StorageException, e:
                print " * direct-files.py : Error updating direct payload"

            self.__messages()
            self.__displayType()

        # Make sure security comes after workflows
        self.__security()

    def __newDoc(self):
        self.oid = self.object.getId()
        self.pid = self.payload.getId()
        metadataPid = self.params.getProperty("metaPid", "DC")

        if self.pid == metadataPid:
            self.itemType = "object"
        else:
            self.oid += "/" + self.pid
            self.itemType = "datastream"
            self.utils.add(self.index, "identifier", self.pid)
            self.utils.add(self.index, "identifier", self.pid)

        self.utils.add(self.index, "id", self.oid)
        self.utils.add(self.index, "storage_id", self.oid)
        self.utils.add(self.index, "item_type", self.itemType)
        self.utils.add(self.index, "last_modified", time.strftime("%Y-%m-%dT%H:%M:%SZ"))
        self.utils.add(self.index, "harvest_config", self.params.getProperty("jsonConfigOid"))
        self.utils.add(self.index, "harvest_rules",  self.params.getProperty("rulesOid"))

        self.item_security = []

    def __basicData(self):
        self.utils.add(self.index, "repository_name", self.params["repository.name"])
        self.utils.add(self.index, "repository_type", self.params["repository.type"])

    def __previews(self):
        self.previewPid = None
        for payloadId in self.object.getPayloadIdList():
            try:
                payload = self.object.getPayload(payloadId)
                if str(payload.getType())=="Thumbnail":
                    self.utils.add(self.index, "thumbnail", payload.getId())
                elif str(payload.getType())=="Preview":
                    self.previewPid = payload.getId()
                    self.utils.add(self.index, "preview", self.previewPid)
                elif str(payload.getType())=="AltPreview":
                    self.utils.add(self.index, "altpreview", payload.getId())
            except Exception, e:
                pass

    def __security(self):
        # Security - Validate internal roles
        roles = self.utils.getRolesWithAccess(self.oid, "derby")
        if roles is not None:
            # For every role currently with access
            for role in roles:
                # Should show up, but during debugging we got a few
                if role != "":
                    if role not in self.item_security:
                        # Their access has been revoked
                        self.__revokeAccess(role)
            # Now for every role that the new step allows access
            for role in self.item_security:
                if role not in roles:
                    # Grant access if new
                    self.__grantAccess(role)
        # No existing security
        else:
            if self.item_security is None:
                # Guest access if none provided so far
                self.__grantAccess("guest")
            else:
                # Otherwise use workflow security
                for role in self.item_security:
                    # Grant access if new
                    self.__grantAccess(role)

        # Security - MUST be 2nd, validated internal roles + DiReCt security
        allRoles = self.utils.getRolesWithAccess(self.oid)
        if allRoles is not None:
            for role in allRoles:
                self.utils.add(self.index, "security_filter", role)

        # Security - User exceptions (as opposed to roles)
        if self.wfSecurityExceptions is not None:
            exceptList = self.wfSecurityExceptions.split(",")
            for exception in exceptList:
                value = exception.strip();
                if value != "":
                    self.utils.add(self.index, "security_exception", value)

        # Ownership
        owner = self.params.getProperty("owner", None)
        if owner is None:
            self.utils.add(self.index, "owner", "system")
        else:
            self.utils.add(self.index, "owner", owner)

    def __indexPath(self, name, path, includeLastPart=True):
        parts = path.split("/")
        length = len(parts)
        if includeLastPart:
            length +=1
        for i in range(1, length):
            part = "/".join(parts[:i])
            if part != "":
                if part.startswith("/"):
                    part = part[1:]
                self.__storeAndIndex(name, part)

    def __indexList(self, name, values):
        for value in values:
            self.__storeAndIndex(name, value)

    def __getNodeValues(self, doc, xPath):
        nodes = doc.selectNodes(xPath)
        valueList = []
        if nodes:
            for node in nodes:
                #remove duplicates:
                nodeValue = node.getText()
                if nodeValue not in valueList:
                    valueList.append(node.getText())
        return valueList

    def __grantAccess(self, newRole):
        schema = self.utils.getAccessSchema("derby");
        schema.setRecordId(self.oid)
        schema.set("role", newRole)
        self.utils.setAccessSchema(schema, "derby")

    def __revokeAccess(self, oldRole):
        schema = self.utils.getAccessSchema("derby");
        schema.setRecordId(self.oid)
        schema.set("role", oldRole)
        self.utils.removeAccessSchema(schema, "derby")

    def __metadata(self):
        self.titleList = []
        self.descriptionList = []
        self.creatorList = []
        self.creationDate = []
        self.contributorList = []
        self.approverList = []
        self.formatList = []
        self.fulltext = []
        self.relationDict = {}
        self.customFields = {}

        # Try our data sources, order matters
        self.__dc()
        self.__aperture()
        self.__ffmpeg()
        self.__workflow()
        self.__filePath()

        # Some defaults if the above failed
        if self.titleList == []:
           self.titleList.append(self.object.getSourceId())
        if self.formatList == []:
            source = self.object.getPayload(self.object.getSourceId())
            self.formatList.append(source.getContentType())

        # Index our metadata finally
        self.__indexList("dc_title", self.titleList)
        self.__indexList("dc_creator", self.creatorList)  #no dc_author in schema.xml, need to check
        self.__indexList("dc_contributor", self.contributorList)
        self.__indexList("dc_description", self.descriptionList)
        self.__indexList("dc_format", self.formatList)
        self.__indexList("dc_date", self.creationDate)
        self.__indexList("full_text", self.fulltext)
        for key in self.customFields:
            self.__indexList(key, self.customFields[key])
        for key in self.relationDict:
            self.__indexList(key, self.relationDict[key])

    def __dc(self):
        ### Check if dc.xml returned from ice exists.
        try:
            dcPayload = self.object.getPayload("dc.xml")
            self.utils.registerNamespace("dc", "http://purl.org/dc/elements/1.1/")
            dcXml = self.utils.getXmlDocument(dcPayload)
            if dcXml is not None:
                self.titleList = self.__getNodeValues(dcXml, "//dc:title")
                self.descriptionList = self.__getNodeValues(dcXml, "//dc:description")
                self.creatorList = self.__getNodeValues(dcXml, "//dc:creator")
                self.contributorList = self.__getNodeValues(dcXml, "//dc:contributor")
                self.creationDate = self.__getNodeValues(dcXml, "//dc:issued")
                # ice metadata stored in dc:relation as key::value
                relationList = self.__getNodeValues(dcXml, "//dc:relation")
                for relation in relationList:
                    key, value = relation.split("::")
                    value = value.strip()
                    key = key.replace("_5f","") #ICE encoding _ as _5f?
                    if self.relationDict.has_key(key):
                        self.relationDict[key].append(value)
                    else:
                        self.relationDict[key] = [value]
        except StorageException, e:
            #print "Failed to index ICE dublin core data (%s)" % str(e)
            pass

    def __aperture(self):
        # Extract from aperture.rdf if exist
        try:
            from org.semanticdesktop.aperture.vocabulary import NCO;
            from org.semanticdesktop.aperture.vocabulary import NFO;
            from org.semanticdesktop.aperture.vocabulary import NID3;
            from org.semanticdesktop.aperture.vocabulary import NIE;
            from org.semanticdesktop.aperture.rdf.impl import RDFContainerImpl;
            from org.ontoware.rdf2go.model.node.impl import URIImpl;

            rdfPayload = self.object.getPayload("aperture.rdf")
            rdfModel = self.utils.getRdfModel(rdfPayload)

            # Seems like aperture only encode the spaces. Tested against special
            # characters file name and it's working
            safeOid = self.oid.replace(" ", "%20")
            rdfId = "urn:oid:%s" % safeOid.rstrip("/")

            container = RDFContainerImpl(rdfModel, rdfId)

            # 1. get title only if no title returned by ICE
            if self.titleList == []:
                titleCollection = container.getAll(NIE.title)
                iterator = titleCollection.iterator()
                while iterator.hasNext():
                    node = iterator.next()
                    result = str(node).strip()
                    self.titleList.append(result)

                titleCollection = container.getAll(NID3.title)
                iterator = titleCollection.iterator()
                while iterator.hasNext():
                    node = iterator.next()
                    result = str(node).strip()
                    self.titleList.append(result)

            # 2. get creator only if no creator returned by ICE
            if self.creatorList == []:
                creatorCollection = container.getAll(NCO.creator);
                iterator = creatorCollection.iterator()
                while iterator.hasNext():
                    node = iterator.next()
                    creatorUri = URIImpl(str(node))
                    creatorContainer = RDFContainerImpl(rdfModel, creatorUri);
                    value = creatorContainer.getString(NCO.fullname);
                    if value and value not in self.creatorList:
                        self.creatorList.append(value)

            # 3. getFullText: only aperture has this information
            fulltextString = container.getString(NIE.plainTextContent)
            if fulltextString:
                self.fulltext.append(fulltextString.strip())
                #4. description/abstract will not be returned by aperture, so if no description found
                # in dc.xml returned by ICE, put first 100 characters
                if self.descriptionList == []:
                    descriptionString = fulltextString
                    if len(fulltextString) > 100:
                        descriptionString = fulltextString[:100] + "..."
                    self.descriptionList.append(descriptionString)

            # 4. album title
            albumTitle = container.getString(NID3.albumTitle)
            if albumTitle:
                self.descriptionList.append("Album: " + albumTitle.strip())

            # 5. mimeType: only aperture has this information
            mimeType = container.getString(NIE.mimeType)
            if mimeType:
                self.formatList.append(mimeType.strip())

            # 6. contentCreated
            if self.creationDate == []:
                contentCreated = container.getString(NIE.contentCreated)
                if contentCreated:
                    self.creationDate.append(contentCreated.strip())
        except StorageException, e:
            #print "Failed to index aperture data (%s)" % str(e)
            pass

    def __ffmpeg(self):
        ### Check if ffmpeg.info exists or not
        try:
            ffmpegPayload = self.object.getPayload("ffmpeg.info")
            ffmpeg = self.utils.getJsonObject(ffmpegPayload.open())
            ffmpegPayload.close()
            if ffmpeg is not None:
                # Dimensions
                width = ffmpeg.get("video/width")
                height = ffmpeg.get("video/height")
                if width is not None and height is not None:
                    self.__storeAndIndex("dc_size", width + " x " + height)

                # Duration
                duration = ffmpeg.get("duration")
                if duration is not None and int(duration) > 0:
                    if int(duration) > 59:
                        secs = int(duration) % 60
                        mins = (int(duration) - secs) / 60
                        self.__storeAndIndex("dc_duration", "%dm %ds" % (mins, secs))
                    else:
                        self.__storeAndIndex("dc_duration", duration + " second(s)")

                # Format
                media = ffmpeg.get("format/label")
                if media is not None:
                    self.__storeAndIndex("dc_media_format", media)

                # Video
                codec = ffmpeg.get("video/codec/simple")
                label = ffmpeg.get("video/codec/label")
                if codec is not None and label is not None:
                    self.__storeAndIndex("video_codec_simple", codec)
                    self.__storeAndIndex("video_codec_label", label)
                    self.__storeAndIndex("meta_video_codec", label + " (" + codec + ")")
                else:
                    if codec is not None:
                        self.__storeAndIndex("video_codec_simple", codec)
                        self.__storeAndIndex("meta_video_codec", codec)
                    if label is not None:
                        self.__storeAndIndex("video_codec_label", label)
                        self.__storeAndIndex("meta_video_codec", label)
                pixel_format = ffmpeg.get("video/pixel_format")
                if pixel_format is not None:
                    self.__storeAndIndex("meta_video_pixel_format", pixel_format)

                # Audio
                codec = ffmpeg.get("audio/codec/simple")
                label = ffmpeg.get("audio/codec/label")
                if codec is not None and label is not None:
                    self.__storeAndIndex("audio_codec_simple", codec)
                    self.__storeAndIndex("audio_codec_label", label)
                    self.__storeAndIndex("meta_audio_codec", label + " (" + codec + ")")
                else:
                    if codec is not None:
                        self.__storeAndIndex("audio_codec_simple", codec)
                        self.__storeAndIndex("meta_audio_codec", codec)
                    if label is not None:
                        self.__storeAndIndex("audio_codec_label", label)
                        self.__storeAndIndex("meta_audio_codec", label)
                sample_rate = ffmpeg.get("audio/sample_rate")
                if sample_rate is not None:
                    sample_rate = "%.1f KHz" % (int(sample_rate) / 1000)
                channels = ffmpeg.get("audio/channels")
                if channels is not None:
                    channels += " Channel(s)"
                if sample_rate is not None and channels is not None:
                    self.__storeAndIndex("meta_audio_details", sample_rate + ", " + channels)
                else:
                    if sample_rate is not None:
                        self.__storeAndIndex("meta_audio_details", sample_rate)
                    if channels is not None:
                        self.__storeAndIndex("meta_audio_details", channels)
        except StorageException, e:
            #print "Failed to index FFmpeg metadata (%s)" % str(e)
            pass

    def __workflow(self):
        # Workflow data
        WORKFLOW_ID = "direct"
        wfChanged = False
        workflow_security = []
        try:
            wfPayload = self.object.getPayload("workflow.metadata")
            wfMeta = JsonConfigHelper(wfPayload.open())
            wfPayload.close()

            # Are we indexing because of a workflow progression?
            targetStep = wfMeta.get("targetStep")
            if targetStep is not None and targetStep != wfMeta.get("step"):
                wfChanged = True
                # Step change
                wfMeta.set("step", targetStep)
                wfMeta.removePath("targetStep")

            # This must be a re-index then
            else:
                targetStep = wfMeta.get("step")

            # Security change
            stages = self.config.getJsonList("stages")
            for stage in stages:
                if stage.get("name") == targetStep:
                    wfMeta.set("label", stage.get("label"))
                    self.item_security = stage.getList("visibility")
                    workflow_security = stage.getList("security")
                    if wfChanged == True:
                        self.message_list = stage.getList("message")

            # Form processing
            formData = wfMeta.getJsonList("formData")
            if formData.size() > 0:
                formData = formData[0]
            else:
                formData = None
            coreFields = ["title", "creator", "contributor", "description", "format", "creationDate"]
            if formData is not None:
                # Core fields
                title = formData.getList("title")
                if title:
                    self.titleList = title
                creator = formData.getList("creator")
                if creator:
                    self.creatorList = creator
                contributor = formData.getList("contributor")
                if contributor:
                    self.contributorList = contributor
                description = formData.getList("description")
                if description:
                    self.descriptionList = description
                format = formData.getList("format")
                if format:
                    self.formatList = format
                creation = formData.getList("creationDate")
                if creation:
                    self.creationDate = creation
                # Non-core fields
                data = formData.getMap("/")
                for field in data.keySet():
                    if field not in coreFields:
                        self.customFields[field] = formData.getList(field)

        except StorageException, e:
            # No workflow payload, time to create
            wfChanged = True
            wfMeta = JsonConfigHelper()
            wfMeta.set("id", WORKFLOW_ID)
            wfMeta.set("step", "pending")
            wfMeta.set("pageTitle", "Uploaded Files - Management")
            stages = self.config.getJsonList("stages")
            for stage in stages:
                if stage.get("name") == "pending":
                    wfMeta.set("label", stage.get("label"))
                    self.item_security = stage.getList("visibility")
                    workflow_security = stage.getList("security")
                    self.message_list = stage.getList("message")

        # Has the workflow metadata changed?
        if wfChanged == True:
            jsonString = String(wfMeta.toString())
            inStream = ByteArrayInputStream(jsonString.getBytes("UTF-8"))
            try:
                StorageUtils.createOrUpdatePayload(self.object, "workflow.metadata", inStream)
            except StorageException, e:
                print " * direct-files.py : Error updating workflow payload"

        # Copyright Notices
        wfCopyright = wfMeta.get("copyright");
        if wfCopyright is not None and wfCopyright == "true":
            wfNotice = wfMeta.get("copyrightNotice");
            if wfNotice is not None:
                self.utils.add(self.index, "user_agreement_text", wfNotice)
                self.utils.add(self.index, "user_agreement_title", "Copyright Notice Agreement")
                self.utils.add(self.index, "user_agreement_accept", "I Accept")
                self.utils.add(self.index, "user_agreement_cancel", "Cancel")

        self.wfSecurityExceptions = wfMeta.get("directSecurityExceptions");

        self.utils.add(self.index, "workflow_id", wfMeta.get("id"))
        self.utils.add(self.index, "workflow_step", wfMeta.get("step"))
        self.utils.add(self.index, "workflow_step_label", wfMeta.get("label"))
        for group in workflow_security:
            self.utils.add(self.index, "workflow_security", group)

    def __filePath(self):
        baseFilePath = self.params["base.file.path"]
        filePath = self.object.getMetadata().getProperty("file.path")
        if baseFilePath:
            # NOTE: need to change again if the json file accept forward
            #       slash in windows
            # Get the base folder
            baseDir = baseFilePath.rstrip("/")
            baseDir = "/%s/" % baseDir[baseDir.rfind("/")+1:]
            filePath = filePath.replace("\\", "/").replace(baseFilePath, baseDir)
        self.__indexPath("file_path", filePath, False)

    def __messages(self):
        if self.message_list is not None and len(self.message_list) > 0:
            msg = JsonConfigHelper()
            msg.set("oid", self.oid)
            message = msg.toString()
            for target in self.message_list:
                self.utils.sendMessage(target, message)

    def __storeAndIndex(self, field, value):
        if value is not None and value != "":
            self.utils.add(self.index, field, value)
            self.directIndex.set(field, value)

    def __displayType(self):
        # check the object metadata for display type set by harvester or transformer
        # otherwise determine the display type by mime type
        displayType = self.params.getProperty("displayType")
        if not displayType:
            displayType = self.formatList[0]
            if displayType is not None:
                self.utils.add(self.index, "display_type", self.utils.basicDisplayType(displayType))
        else:
            self.utils.add(self.index, "display_type", displayType)
        # Some object use a special preview template. eg. word docs with a html preview
        previewType = self.params.getProperty("previewType")
        if not previewType:
            previewType = self.utils.getDisplayMimeType(self.formatList, self.object, self.previewPid)
            if previewType is not None and previewType != displayType:
                self.utils.add(self.index, "preview_type", self.utils.basicDisplayType(previewType))
        else:
            self.utils.add(self.index, "preview_type", previewType)
