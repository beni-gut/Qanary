package eu.wdaqua.qanary.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

import eu.wdaqua.qanary.commons.QanaryMessage;
import eu.wdaqua.qanary.commons.QanaryQuestion;
import eu.wdaqua.qanary.QanaryComponentRegistrationChangeNotifier;
import eu.wdaqua.qanary.business.*;
import eu.wdaqua.qanary.exceptions.SparqlQueryFailed;


/**
 * controller for processing questions, i.e., related to the question answering process
 *
 * @author Dennis Diefenbach
 */
@Controller
public class QanaryGerbilController {
	
    private static final Logger logger = LoggerFactory.getLogger(QanaryGerbilController.class);
    private final QanaryConfigurator qanaryConfigurator;
	private final QanaryComponentRegistrationChangeNotifier qanaryComponentRegistrationChangeNotifier;
 
     private String host;
     private int port;
     
 
    //Set this to allow browser requests from other websites
    @ModelAttribute
    public void setVaryResponseHeader(HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*");
    }

    /**
     * inject QanaryConfigurator
     */
    @Autowired
    public QanaryGerbilController(final QanaryConfigurator qanaryConfigurator,
    							  final QanaryPipelineConfiguration qanaryPipelineConfiguration, 
                                  final QanaryQuestionController qanaryQuestionController,
                                  final QanaryComponentRegistrationChangeNotifier qanaryComponentRegistrationChangeNotifier) {
        this.qanaryConfigurator = qanaryConfigurator;
        this.qanaryComponentRegistrationChangeNotifier = qanaryComponentRegistrationChangeNotifier;
        this.host = qanaryPipelineConfiguration.getHost();
        this.port = qanaryPipelineConfiguration.getPort();
    }

    /**
     * expose the model with the component names
     */
    @ModelAttribute("componentList")
    public List<String> componentList() {
    	List<String> components = qanaryComponentRegistrationChangeNotifier.getAvailableComponentNames();
        logger.info("available components: {}", components);
        return components;
    }

    /**
     * a simple HTML input to generate a url-endpoint for gerbil for QA, http://gerbil-qa.aksw.org/gerbil/config
     */
    @RequestMapping(value = "/gerbil", method = RequestMethod.GET)
    public String startquestionansweringwithtextquestion(Model model) {
        model.addAttribute("url", "Select components!");
        return "generategerbilendpoint";
    }

    /**
     * given a list of components a url-endpoint for gerbil for QA is generated
     *
     */
    @RequestMapping(value = "/gerbil", method = RequestMethod.POST)
    public String gerbilGenerator(
            @RequestParam(value = QanaryStandardWebParameters.COMPONENTLIST, defaultValue = "") final List<String> componentsToBeCalled,
            Model model
    ) throws Exception {
        String urlStr = "";
        if (componentsToBeCalled.size()==0){
            urlStr = "Select components!";
            model.addAttribute("url", urlStr);
        } else {
            //Generate a string like this "wdaqua-core0, QueryExecuter"
            String components = "/gerbil-execute/";
            for (String component : componentsToBeCalled) {
                components += component + ", ";
            }
            logger.info("components (0): {}",components);
            if (components.length() > 0) {
                components = components.substring(0, components.length() - 2);
            }
            logger.info("compoents (1): {}",components);
            //urlStr += URLEncoder.encode(components, "UTF-8")+"/";
            URI uri = new URI(
                    "http",
                    null,
                    new URL(host).getHost(),
                    port,
                    components+"/",
                    null,
                    null);
            URL url = uri.toURL();
            logger.info("created URL: {}", url.toString());
            model.addAttribute("url", url.toString());
        }
        return "generategerbilendpoint";
    }

    @SuppressWarnings("unchecked")
	@RequestMapping(value="/gerbil-execute/{components:.*}",  method = RequestMethod.POST, produces = "application/json")
	public ResponseEntity<?> gerbil(
			@RequestParam(value = "query", required = true) final String query,
            @RequestParam(value = "lang", required = true) final String queryLanguage,
            @PathVariable("components") final String componentsToBeCalled
    ) throws URISyntaxException, Exception, SparqlQueryFailed, ParseException {
    	logger.info("Asked question: {}", query);
    	logger.info("Language of question: {}", queryLanguage);
        logger.info("QA pipeline components: {}", componentsToBeCalled);
    	MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
        map.add("question", query);
        map.add("language", queryLanguage);
        map.add("componentlist[]", componentsToBeCalled);
        RestTemplate restTemplate = new RestTemplate();
        String response = restTemplate.postForObject(qanaryConfigurator.getHost()+":"+qanaryConfigurator.getPort()+"/startquestionansweringwithtextquestion", map, String.class);
        org.json.JSONObject json = new org.json.JSONObject(response);
        //retrieve text representation, SPARQL and JSON result
        QanaryMessage myQanaryMessage = new QanaryMessage(new URI((String)json.get("endpoint")), new URI((String)json.get("inGraph")), new URI((String)json.get("outGraph")));
        @SuppressWarnings("rawtypes")
		QanaryQuestion<?> myQanaryQuestion = new QanaryQuestion(myQanaryMessage);
        //Generates the following output
        /*{
            "questions": [{
                "question": [{
                    "language": "en",   //(ISO 639-1)
          			"string": "..."     //(textual representation of asked Question)
          			    }],
          		"query": {
          		    "sparql": "..."     //(SPARQL Query constructed by QB component)
                },
          		"answers": [{"..."}]    //(Answers returned by QE component)
            }]
        }*/
        // retrieve the content (question, SPARQL Query and answer)
        // getTextualRepresentation needs Exception to be thrown
        // tries to retrieve the language from Triplestore, if not retrievable use "en" as default
        String language = "en";
        try {
            String annotatedLang = myQanaryQuestion.getLanguage();
            if (annotatedLang != null && !annotatedLang.isEmpty()) {
                language = annotatedLang;
            }
        } catch (Exception e) {
            logger.warn("Could not retrieve language from triplestore, using \"{}\" instead", language);
        }
        String questionText = myQanaryQuestion.getTextualRepresentation();
        String sparqlQueryString = myQanaryQuestion.getSparqlResult();      // returns empty String if no Query was found
        String jsonAnswerString = myQanaryQuestion.getJsonResult();         // returns empty String if no answer was found

        // create the question array and object and add the content
        JSONArray questionDataArray = new JSONArray();
        JSONObject questionData = new JSONObject();
        questionData.put("language", language);
        questionData.put("string", questionText);
        questionDataArray.add(questionData);

        // create the query object and add the content
        JSONObject queryObj = new JSONObject();
        queryObj.put("sparql", sparqlQueryString);

        // transform the answer String to JSON, if an answer was found
        JSONObject answersObj = new JSONObject();
        if (jsonAnswerString != null && jsonAnswerString.length() > 0) {
            JSONParser parser = new JSONParser();
            answersObj = (JSONObject) parser.parse(jsonAnswerString);
        }

        // create the answers array and add the content
        JSONArray answersArray = new JSONArray();
        answersArray.add(answersObj);

        // create the wrapper object and the array and add all JSON to it
        JSONArray questionsArray = new JSONArray();
        JSONObject questionObject = new JSONObject();
        questionObject.put("question", questionDataArray);
        questionObject.put("query", queryObj);
        questionObject.put("answers", answersArray);
        questionsArray.add(questionObject);

        // add all to the wrapper object
        JSONObject obj = new JSONObject();
        obj.put("questions", questionsArray);

        return new ResponseEntity<JSONObject>(obj,HttpStatus.OK);
	}
}
