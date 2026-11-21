const createEmscriptenProxy = (VerovioModule) => {
    return new Proxy({}, {
        get: (target, method) => {
            return (...args) => {
                return getToolkitFunction(VerovioModule, method)(...args);
            };
        },
    });
};

function getToolkitFunction(VerovioModule, method) {
    const mapping = {};

    // Constructor and destructor
    // Toolkit *constructor()
    mapping.constructor = VerovioModule.cwrap("vrvToolkit_constructor", "number", []);

    // void destructor(Toolkit *ic)
    mapping.destructor = VerovioModule.cwrap("vrvToolkit_destructor", null, ["number"]);

    // bool edit(Toolkit *ic, const char *editorAction) 
    mapping.edit = VerovioModule.cwrap("vrvToolkit_edit", "number", ["number", "string"]);

    // char *editResponse(Toolkit *ic)
    mapping.editResponse = VerovioModule.cwrap("vrvToolkit_editResponse", "string", ["number"]);

    // char *editStatus(Toolkit *ic)
    mapping.editStatus = VerovioModule.cwrap("vrvToolkit_editStatus", "string", ["number"]);

    // char *getAvailableOptions(Toolkit *ic)
    mapping.getAvailableOptions = VerovioModule.cwrap("vrvToolkit_getAvailableOptions", "string", ["number"]);

    // char *getDefaultOptions(Toolkit *ic)
    mapping.getDefaultOptions = VerovioModule.cwrap("vrvToolkit_getDefaultOptions", "string", ["number"]);

    // char *getDescriptiveFeatures(Toolkit *ic, const char *options)
    mapping.getDescriptiveFeatures = VerovioModule.cwrap("vrvToolkit_getDescriptiveFeatures", "string", ["number", "string"]);

    // char *getElementAttr(Toolkit *ic, const char *xmlId)
    mapping.getElementAttr = VerovioModule.cwrap("vrvToolkit_getElementAttr", "string", ["number", "string"]);

    // char *getElementsAtTime(Toolkit *ic, int time)
    mapping.getElementsAtTime = VerovioModule.cwrap("vrvToolkit_getElementsAtTime", "string", ["number", "number"]);

    // char *vrvToolkit_getExpansionIdsForElement(Toolkit *tk, const char *xmlId);
    mapping.getExpansionIdsForElement = VerovioModule.cwrap("vrvToolkit_getExpansionIdsForElement", "string", ["number", "string"]);

    // char *getHumdrum(Toolkit *ic)
    mapping.getHumdrum = VerovioModule.cwrap("vrvToolkit_getHumdrum", "string", ["number"]);

    // char *convertMEIToHumdrum(Toolkit *ic, const char *meiData)
    mapping.convertMEIToHumdrum = VerovioModule.cwrap("vrvToolkit_convertMEIToHumdrum", "string", ["number", "string"]);

    // char *convertHumdrumToHumdrum(Toolkit *ic, const char *humdrumData)
    mapping.convertHumdrumToHumdrum = VerovioModule.cwrap("vrvToolkit_convertHumdrumToHumdrum", "string", ["number", "string"]);

    // char *convertHumdrumToMIDI(Toolkit *ic, const char *humdrumData)
    mapping.convertHumdrumToMIDI = VerovioModule.cwrap("vrvToolkit_convertHumdrumToMIDI", "string", ["number", "string"]);

    // char *getLog(Toolkit *ic)
    mapping.getLog = VerovioModule.cwrap("vrvToolkit_getLog", "string", ["number"]);

    // char *getMEI(Toolkit *ic, const char *options)
    mapping.getMEI = VerovioModule.cwrap("vrvToolkit_getMEI", "string", ["number", "string"]);

    // char *vrvToolkit_getNotatedIdForElement(Toolkit *tk, const char *xmlId);
    mapping.getNotatedIdForElement = VerovioModule.cwrap("vrvToolkit_getNotatedIdForElement", "string", ["number", "string"]);

    // char *getOptions(Toolkit *ic)
    mapping.getOptions = VerovioModule.cwrap("vrvToolkit_getOptions", "string", ["number"]);

    // int getPageCount(Toolkit *ic)
    mapping.getPageCount = VerovioModule.cwrap("vrvToolkit_getPageCount", "number", ["number"]);

    // int getPageWithElement(Toolkit *ic, const char *xmlId)
    mapping.getPageWithElement = VerovioModule.cwrap("vrvToolkit_getPageWithElement", "number", ["number", "string"]);

    // double getTimeForElement(Toolkit *ic, const char *xmlId)
    mapping.getTimeForElement = VerovioModule.cwrap("vrvToolkit_getTimeForElement", "number", ["number", "string"]);

    // char *getTimesForElement(Toolkit *ic, const char *xmlId)
    mapping.getTimesForElement = VerovioModule.cwrap("vrvToolkit_getTimesForElement", "string", ["number", "string"]);

    // char *getMIDIValuesForElement(Toolkit *ic, const char *xmlId)
    mapping.getMIDIValuesForElement = VerovioModule.cwrap("vrvToolkit_getMIDIValuesForElement", "string", ["number", "string"]);

    // char *getVersion(Toolkit *ic)
    mapping.getVersion = VerovioModule.cwrap("vrvToolkit_getVersion", "string", ["number"]);

    // bool loadData(Toolkit *ic, const char *data)
    mapping.loadData = VerovioModule.cwrap("vrvToolkit_loadData", "number", ["number", "string"]);

    // bool loadZipDataBase64(Toolkit *ic, const char *data)
    mapping.loadZipDataBase64 = VerovioModule.cwrap("vrvToolkit_loadZipDataBase64", "number", ["number", "string"]);

    // bool loadZipDataBuffer(Toolkit *ic, const unsigned char *data, int length)
    mapping.loadZipDataBuffer = VerovioModule.cwrap("vrvToolkit_loadZipDataBuffer", "number", ["number", "number", "number"]);

    // void redoLayout(Toolkit *ic)
    mapping.redoLayout = VerovioModule.cwrap("vrvToolkit_redoLayout", null, ["number", "string"]);

    // void redoPagePitchPosLayout(Toolkit *ic)
    mapping.redoPagePitchPosLayout = VerovioModule.cwrap("vrvToolkit_redoPagePitchPosLayout", null, ["number"]);

    // char *renderData(Toolkit *ic, const char *data, const char *options)
    mapping.renderData = VerovioModule.cwrap("vrvToolkit_renderData", "string", ["number", "string", "string"]);

    // char *renderToExpansionMap(Toolkit *ic)
    mapping.renderToExpansionMap = VerovioModule.cwrap("vrvToolkit_renderToExpansionMap", "string", ["number"]);

    // char *renderToMIDI(Toolkit *ic)
    mapping.renderToMIDI = VerovioModule.cwrap("vrvToolkit_renderToMIDI", "string", ["number"]);

    // char *renderToPAE(Toolkit *ic)
    mapping.renderToPAE = VerovioModule.cwrap("vrvToolkit_renderToPAE", "string", ["number"]);

    // char *renderToSvg(Toolkit *ic, int pageNo, int xmlDeclaration)
    mapping.renderToSVG = VerovioModule.cwrap("vrvToolkit_renderToSVG", "string", ["number", "number", "number"]);

    // char *renderToTimemap(Toolkit *ic)
    mapping.renderToTimemap = VerovioModule.cwrap("vrvToolkit_renderToTimemap", "string", ["number", "string"]);

    // void resetOptions(Toolkit *ic)
    mapping.resetOptions = VerovioModule.cwrap("vrvToolkit_resetOptions", null, ["number"]);

    // void resetXmlIdSeed(Toolkit *ic, int seed) 
    mapping.resetXmlIdSeed = VerovioModule.cwrap("vrvToolkit_resetXmlIdSeed", null, ["number", "number"]);

    // bool select(Toolkit *ic, const char *options) 
    mapping.select = VerovioModule.cwrap("vrvToolkit_select", "number", ["number", "string"]);

    // void setOptions(Toolkit *ic, const char *options) 
    mapping.setOptions = VerovioModule.cwrap("vrvToolkit_setOptions", null, ["number", "string"]);

    // char *validatePAE(Toolkit *ic, const char *options)
    mapping.validatePAE = VerovioModule.cwrap("vrvToolkit_validatePAE", "string", ["number", "string"]);

    return mapping[method];
}

// @ts-check


/** @typedef {import("./types/VerovioOptions").VerovioOptions} VerovioOptions */
/** @typedef {import("./types/EditorTypes").EditorAction} EditorAction */
/** @typedef {import("./types/EditorTypes").EditorStatus} EditorStatus */
/** @typedef {import("./types/EditorTypes").EditorResponse} EditorResponse */
/** @typedef {import("./types/AvailableOptions").AvailableOptions} AvailableOptions */
/** @typedef {import("./types/VerovioTypes").MIDIValues} MIDIValues */
/** @typedef {import("./types/VerovioTypes").GetMeiOptions} GetMeiOptions */
/** @typedef {import("./types/VerovioTypes").TimeMapEntry} TimeMapEntry */
/** @typedef {import("./types/VerovioTypes").TimeMapOptions} TimeMapOptions */
/** @typedef {import("./types/VerovioTypes").RedoLayoutOptions} RedoLayoutOptions */
/** @typedef {import("./types/VerovioTypes").Selection} Selection */
/** @typedef {import("./types/VerovioTypes").PAEValidation} PAEValidation */
/** @typedef {import("./types/VerovioTypes").ExpansionMap} ExpansionMap */
/** @typedef {import("./types/VerovioTypes").DescriptiveFeatures} DescriptiveFeatures */
/** @typedef {import("./types/VerovioTypes").DescriptiveFeaturesOptions} DescriptiveFeaturesOptions */
/** @typedef {import("./types/VerovioTypes").ElementTimes} ElementTimes */
/** @typedef {import("./types/VerovioModule").VerovioModule} VerovioModule */

class VerovioToolkit {

    /** @param {VerovioModule} [VerovioModule] */
    constructor(VerovioModule) {
        if (!VerovioModule) {
            throw new Error("VerovioToolkit needs VerovioModule passed as argument to the constructor.");
        }
        /** @private @type {VerovioModule} */
        this.VerovioModule = VerovioModule;
        /** @private @type {any} */
        this.proxy = createEmscriptenProxy(this.VerovioModule);
        /** @private @type {number} */
        this.ptr = this.proxy.constructor();
        VerovioToolkit.instances.push(this);
    }

    destroy() {
        VerovioToolkit.instances.splice(VerovioToolkit.instances.findIndex(i => i.ptr === this.ptr), 1);
        this.proxy.destructor(this.ptr);
    }

    /**
     * Edit the MEI data.
     * @param {EditorAction} editorAction The editor action as a JSON object
     * @returns {boolean} True if the edit action was successfully applied
     */
    edit(editorAction) {
        return this.proxy.edit(this.ptr, JSON.stringify(editorAction));
    }

    /**
     * Return the editor status.
     * @deprecated Use editStatus instead.
     * @returns {EditorStatus} The editor status
     */
    editInfo() {
        console.warn( "This function is deprecated. Use editStatus instead." );
        return JSON.parse(this.proxy.editStatus(this.ptr));
    }

    /**
     * Return the editor response.
     * @returns {EditorResponse} The editor response
     */
    editResponse() {
        return JSON.parse(this.proxy.editResponse(this.ptr));
    }

    /**
     * Return the editor status.
     * @returns {EditorStatus} The editor status
     */
    editStatus() {
        return JSON.parse(this.proxy.editStatus(this.ptr));
    }

    /**
     * Return all available options grouped by category.
     *
     * For each option, returns the type, the default value, and the minimum and maximum value (when available)
     * @returns {AvailableOptions} The available options
     */
    getAvailableOptions() {
        return JSON.parse(this.proxy.getAvailableOptions(this.ptr));
    }

    /**
     * Return a dictionary of all the options with their default value.
     * @returns {VerovioOptions} The default options
     */
    getDefaultOptions() {
        return JSON.parse(this.proxy.getDefaultOptions(this.ptr));
    }

    /**
     * Return descriptive features as a JSON string.
     *
     * The features are tailored for implementing incipit search.
     * @param {DescriptiveFeaturesOptions} options A JSON object with the feature extraction options
     * @returns {DescriptiveFeatures} A JSON object with the requested features
     */
    getDescriptiveFeatures(options) {
        return JSON.parse(this.proxy.getDescriptiveFeatures(this.ptr, JSON.stringify(options)));
    }

    /**
     * Return element attributes as a JSON string.
     *
     * The attributes returned include the ones not supported by Verovio
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {Object<string, string>} A JSON object with all attributes
     */
    getElementAttr(xmlId) {
        return JSON.parse(this.proxy.getElementAttr(this.ptr, xmlId));
    }

    /**
     * Returns array of IDs of elements being currently played.
     * @param {number} millisec The time in milliseconds
     * @returns {{notes: string[], page: number}} A JSON object with the page and notes being played
     */
    getElementsAtTime(millisec) {
        return JSON.parse(this.proxy.getElementsAtTime(this.ptr, millisec));
    }

    /**
     * Returns a vector of ID strings of all elements (the notated and the expanded) for a given element.
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {Object<string, string>} A JSON object with all IDs
     */
    getExpansionIdsForElement(xmlId) {
        return JSON.parse(this.proxy.getExpansionIdsForElement(this.ptr, xmlId));
    }

    /**
     * Get the humdrum buffer.
     * @returns {string} The humdrum buffer as a string
     */
    getHumdrum() {
        return this.proxy.getHumdrum(this.ptr);
    }

    /**
     * Filter Humdrum data.
     * @param {string} data The Humdrum data
     * @returns {string} The Humdrum data as a string
     */
    convertHumdrumToHumdrum(data) {
        return this.proxy.convertHumdrumToHumdrum(this.ptr, data);
    }

    /**
     * Convert Humdrum data to MIDI.
     * @param {string} data The Humdrum data
     * @returns {string} The MIDI file as a base64-encoded string
     */
    convertHumdrumToMIDI(data) {
        return this.proxy.convertHumdrumToMIDI(this.ptr, data);
    }

    /**
     * Convert MEI data into Humdrum data.
     * @param {string} data The MEI data
     * @returns {string} The Humdrum data as a string
     */
    convertMEIToHumdrum(data) {
        return this.proxy.convertMEIToHumdrum(this.ptr, data);
    }

    /**
     * Get the log content for the latest operation.
     * @returns {string} The log content as a string
     */
    getLog() {
        return this.proxy.getLog(this.ptr);
    }

    /**
     * Get the MEI as a string.
     * @param {GetMeiOptions} [options] A JSON object with the output options; pageNo: integer; (1-based), all pages if none (or 0) specified; scoreBased: true or false; true by default; basic: true or false; false by default; removeIds: true or false; false by default - remove all @xml:id not used in the data;
     * @returns {string} The MEI as a string
     */
    getMEI(options = {}) {
        return this.proxy.getMEI(this.ptr, JSON.stringify(options));
    }

    /**
     * Return MIDI values of the element with the ID (xml:id)
     *
     * RenderToMIDI() must be called prior to using this method
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {MIDIValues} a JSON object with the MIDI values
     */
    getMIDIValuesForElement(xmlId) {
        return JSON.parse(this.proxy.getMIDIValuesForElement(this.ptr, xmlId));
    }

    /**
     * Returns the ID string of the notated (the original) element.
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {string} An ID string
     */
    getNotatedIdForElement(xmlId) {
        return this.proxy.getNotatedIdForElement(this.ptr, xmlId);
    }

    /**
     * Return a dictionary of all the options with their current value.
     * @param {boolean} [defaultValues] Deprecated: use getDefaultOptions() (with 'true') or no argument instead.
     * @returns {VerovioOptions} A JSON object with the current options
     */
    getOptions(defaultValues) {
        if (defaultValues === true) {
            console.warn( "This function (with 'true' parameter) is deprecated. Use getDefaultOptions() instead." );
            return JSON.parse(this.proxy.getDefaultOptions(this.ptr));
        }
        else if (defaultValues === false) {
            console.warn( "This function (with 'false' parameter) is deprecated. Use getOptions() instead." );
            return JSON.parse(this.proxy.getOptions(this.ptr));
        }
        else {
            return JSON.parse(this.proxy.getOptions(this.ptr));
        }
    }

    /**
     * Return the number of pages in the loaded document.
     *
     * The number of pages depends one the page size and if encoded layout was taken into account or not.
     * @returns {number} The number of pages
     */
    getPageCount() {
        return this.proxy.getPageCount(this.ptr);
    }

    /**
     * Return the page on which the element is the ID (xml:id) is rendered.
     *
     * This takes into account the current layout options.
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {number} the page number (1-based) where the element is (0 if not found)
     */
    getPageWithElement(xmlId) {
        return this.proxy.getPageWithElement(this.ptr, xmlId);
    }

    /**
     * Return the time at which the element is the ID (xml:id) is played.
     *
     * RenderToMIDI() must be called prior to using this method.
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {number} The time in milliseconds
     */
    getTimeForElement(xmlId) {
        return this.proxy.getTimeForElement(this.ptr, xmlId);
    }

    /**
     * Return a JSON object string with the following key values for a given note.
     *
     * Return scoreTimeOnset, scoreTimeOffset, scoreTimeTiedDuration, realTimeOnsetMilliseconds, realTimeOffsetMilliseconds, realTimeTiedDurationMilliseconds.
     * @param {string} xmlId the ID (@xml:id) of the element being looked for
     * @returns {ElementTimes} A JSON object with the values
     */
    getTimesForElement(xmlId) {
        return JSON.parse(this.proxy.getTimesForElement(this.ptr, xmlId));
    }

    /**
     * Return the version number.
     * @returns {string} the version number as a string
     */
    getVersion() {
        return this.proxy.getVersion(this.ptr);
    }

    /**
     * Load a string data with the type previously specified in the options.
     *
     * By default, the methods try to auto-detect the type.
     * @param {string} data A string with the data (e.g., MEI data) to be loaded
     * @returns {boolean} True if the data was successfully loaded
     */
    loadData(data) {
        return this.proxy.loadData(this.ptr, data);
    }

    /**
     * Load a MusicXML compressed file passed as base64 encoded string.
     * @param {string} data A ZIP file as a base64 encoded string
     * @returns {boolean} True if the data was successfully loaded
     */
    loadZipDataBase64(data) {
        return this.proxy.loadZipDataBase64(this.ptr, data);
    }

    /**
     * Load a MusicXML compressed file passed as a buffer of bytes.
     * @param {ArrayBuffer} data A ZIP file as a buffer of bytes
     * @returns {boolean} True if the data was successfully loaded
     */
    loadZipDataBuffer(data) {
        if (!(data instanceof ArrayBuffer)) {
            console.error("Parameter for loadZipDataBuffer has to be of type ArrayBuffer");
            return false;
        }
        var dataArray = new Uint8Array(data);
        var dataSize = dataArray.length * dataArray.BYTES_PER_ELEMENT;
        var dataPtr = this.VerovioModule._malloc(dataSize);
        this.VerovioModule.HEAPU8.set(dataArray, dataPtr);
        var res = this.proxy.loadZipDataBuffer(this.ptr, dataPtr, dataSize);
        this.VerovioModule._free(dataPtr);
        return res;
    }

    /**
     * Redo the layout of the loaded data.
     *
     * This can be called once the rendering option were changed, for example with a new page (sceen) height or a new zoom level.
     * @param {RedoLayoutOptions} [options] A JSON object with the action options resetCache: true or false; true by default;
     */
    redoLayout(options = {}) {
        this.proxy.redoLayout(this.ptr, JSON.stringify(options));
    }

    /**
     * Redo the layout of the pitch postitions of the current drawing page.
     *
     * Only the note vertical positions are recalculated with this method. RedoLayout() needs to be called for a full recalculation.
     */
    redoPagePitchPosLayout() {
        this.proxy.redoPagePitchPosLayout(this.ptr);
    }

    /**
     * Render the first page of the data to SVG.
     *
     * This method is a wrapper for setting options, loading data and rendering the first page. It will return an empty string if the options cannot be set or the data cannot be loaded.
     * @param {string} data A string with the data (e.g., MEI data) to be loaded
     * @param {VerovioOptions} options A JSON object with the output options
     * @returns {string} The SVG first page as a string
     */
    renderData(data, options) {
        return this.proxy.renderData(this.ptr, data, JSON.stringify(options));
    }

    /**
     * Render a document’s expansionMap, if existing.
     * @returns {ExpansionMap} The expansion map as a JSON object
     */
    renderToExpansionMap() {
        return JSON.parse(this.proxy.renderToExpansionMap(this.ptr));
    }

    /**
     * Render the document to MIDI.
     * @returns {string} A MIDI file as a base64 encoded string
     */
    renderToMIDI() {
        return this.proxy.renderToMIDI(this.ptr);
    }

    /**
     * Render a document to Plaine and Easie code.
     *
     * Only the top staff / layer is exported.
     * @returns {string} The PAE as a string
     */
    renderToPAE() {
        return this.proxy.renderToPAE(this.ptr);
    }

    /**
     * Render a page to SVG.
     * @param {number} [pageNo] The page to render (1-based), default 1
     * @param {boolean} [xmlDeclaration] True for including the xml declaration in the SVG output, default false
     * @returns {string} The SVG page as a string
     */
    renderToSVG(pageNo = 1, xmlDeclaration = false) {
        return this.proxy.renderToSVG(this.ptr, pageNo, xmlDeclaration);
    }

    /**
     * Render a document to a timemap.
     * @param {TimeMapOptions} [options] A stringified JSON objects with the timemap options
     * @returns {TimeMapEntry[]} The timemap as a JSON object
     */
    renderToTimemap(options = {}) {
        return JSON.parse(this.proxy.renderToTimemap(this.ptr, JSON.stringify(options)));
    }

    /**
     * Reset all options to default values.
     */
    resetOptions() {
        this.proxy.resetOptions(this.ptr);
    }

    /**
     * Reset the seed used to generate MEI @xml:id attribute values.
     *
     * Passing 0 will seed the @xml:id generator with a random (time-based) seed value. This method will have no effect if the xml-id-checksum option is set.
     * @param {number} seed The seed value for generating the @xml:id values (0 for a time-based random seed)
     * @returns {void}
     */
    resetXmlIdSeed(seed) {
        return this.proxy.resetXmlIdSeed(this.ptr, seed);
    }

    /**
     * Set the value for a selection.
     *
     * The selection will be applied only when some data is loaded or the layout is redone. The selection can be reset (cancelled) by passing an empty string or an empty JSON object. A selection across multiple mdivs is not possible.
     * @param {Selection} selection The selection as a stringified object
     * @returns {boolean} True if the selection was successfully parsed or reset
     */
    select(selection) {
        return this.proxy.select(this.ptr, JSON.stringify(selection));
    }

    /**
     * Set option values.
     *
     * The name of each option to be set is to be given as JSON key.
     * @param {VerovioOptions} options A JSON object with the output options
     * @returns {boolean} True if the options were successfully set
     */
    setOptions(options) {
        options = this.preprocessOptions(options);
        return this.proxy.setOptions(this.ptr, JSON.stringify(options));
    }

    /**
     * Validate the Plaine & Easie code passed in the string data.
     *
     * A single JSON object is returned when there is a global input error. When reading the input succeeds, validation is grouped by input keys. The methods always returns errors in PAE pedantic mode. No data remains loaded after the validation.
     * @param {string | Object<string, string>} data A string with the data in JSON or with PAE @ keys
     * @returns {PAEValidation} A JSON object with the validation warnings or errors
     */
    validatePAE(data) {
        if (data instanceof Object) {
            data = /** @type {string} */ (JSON.stringify(data));
        }
        return JSON.parse(this.proxy.validatePAE(this.ptr, data));
    }

    /**
     * @private
     * @param {VerovioOptions} options
     * @returns {VerovioOptions}
     */
    preprocessOptions(options) {
        // Nothing to do if we do not have 'fontAddCustom' set
        if (!options.hasOwnProperty('fontAddCustom')) {
            return options;
        }
        const files = /** @type {string[]} */ (options['fontAddCustom']);
        let filesInBase64 = [];
        // Get all the files and convert them to a base64 string if necessary
        for ( const file of files ) {
            // The file is already passed as base64 string - nothing to do
            if (!/^https?:\/\//.test( file )) {
                filesInBase64.push( file );
                continue;
            }
            const request = new XMLHttpRequest();
            request.open("GET", file, false); // `false` makes the request synchronous
            request.send(null);

            if (request.status === 200) {
                filesInBase64.push(request.responseText);
            }
            else {
                console.error(`${file} could not be retrieved`);
            }
        }
        options["fontAddCustom"] = filesInBase64;
        return options;
    }
}

// A pointer to the object - only one instance can be created for now
/** @type {VerovioToolkit[]} */
VerovioToolkit.instances = [];

const LOG_OFF = 0;
const LOG_ERROR = 1;
const LOG_WARNING = 2;
const LOG_INFO = 3;
const LOG_DEBUG = 4;

function enableLog(level, VerovioModule) {
    return VerovioModule.cwrap("enableLog", null, ["number"])(level);
}

function enableLogToBuffer(value, VerovioModule) {
    return VerovioModule.cwrap("enableLogToBuffer", null, ["number"])(value);
}

export { LOG_DEBUG, LOG_ERROR, LOG_INFO, LOG_OFF, LOG_WARNING, VerovioToolkit, enableLog, enableLogToBuffer };
