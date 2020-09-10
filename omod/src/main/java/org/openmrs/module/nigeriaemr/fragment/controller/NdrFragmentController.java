package org.openmrs.module.nigeriaemr.fragment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.nigeriaemr.Consumer;
import org.openmrs.module.nigeriaemr.api.service.NigeriaPatientService;
import org.openmrs.module.nigeriaemr.api.service.NigeriaemrService;
import org.openmrs.module.nigeriaemr.model.NDRExport;
import org.openmrs.module.nigeriaemr.ndrUtils.LoggerUtils;
import org.openmrs.module.nigeriaemr.ndrUtils.Utils;
import org.openmrs.module.nigeriaemr.omodmodels.DBConnection;
import org.openmrs.module.nigeriaemr.omodmodels.FacilityLocation;
import org.openmrs.module.nigeriaemr.omodmodels.LocationModel;
import org.openmrs.module.nigeriaemr.omodmodels.Version;
import org.openmrs.module.nigeriaemr.service.FacilityLocationService;
import org.openmrs.module.nigeriaemr.service.NdrExtractionService;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NdrFragmentController {
	
	NigeriaPatientService nigeriaPatientService = Context.getService(NigeriaPatientService.class);
	
	NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
	
	DBConnection openmrsConn;
	
	FacilityLocationService facilityLocationService;
	
	ObjectMapper mapper = new ObjectMapper();
	
	NdrExtractionService ndrExtractionService;
	
	public NdrFragmentController() throws Exception {
		ndrExtractionService = new NdrExtractionService();
		openmrsConn = Utils.getNmrsConnectionDetails();
		facilityLocationService = new FacilityLocationService();
	}
	
	public void controller() {
		
	}
	
	public String generateNDRFileByLocation(HttpServletRequest request,
	        @RequestParam(value = "locationId") Integer locationId) throws Exception {
		// get date that's bounds to the date the export is kicked off
		Date currentDate = new Date();
		
		FacilityLocation facilityLocation = facilityLocationService.getFacilityLocationByLocationId(locationId).get(0);
		List<Integer> filteredPatientByLocation = facilityLocationService.getPatientLocationById(locationId);
		
		//check if global variable for logging exists
		LoggerUtils.checkLoggerGlobalProperty(openmrsConn);
		LoggerUtils.clearLogFile();
		
		String FacilityType = "FAC";
		
		if (filteredPatientByLocation.size() == 0)
			return "";
		
		return startGenerateFile(request, filteredPatientByLocation, facilityLocation.getDatimCode(), null, currentDate);
	}
	
	public String generateNDRFile(HttpServletRequest request) throws Exception {
		// get date that's bounds to the date the export is kicked off
		Date currentDate = new Date();
		
		DBConnection openmrsConn = Utils.getNmrsConnectionDetails();
		
		//check if global variable for logging exists
		LoggerUtils.checkLoggerGlobalProperty(openmrsConn);
		LoggerUtils.clearLogFile();
		LoggerUtils.checkPatientLimitGlobalProperty(openmrsConn);
		List<Integer> patients;
		Date lastDate = Utils.getLastNDRDate();
		//		String patientIdLimit = Utils.getPatientIdLimit();
		//		if (patientIdLimit != null && !"".equals(patientIdLimit)) {
		//			String[] patientIdArray = patientIdLimit.split(",");
		//			int startIndex = Integer.parseInt(patientIdArray[0]);
		//			int endIndex = Integer.parseInt(patientIdArray[1]);
		//			patients = nigeriaPatientService.getPatientIdsInIndex(startIndex, endIndex);
		//		} else {
		//			patients = nigeriaPatientService.getPatientIdsByEncounterDate(lastDate, currentDate);
		//		}
		patients = nigeriaPatientService.getPatientIdsByEncounterDate(lastDate, currentDate);
		String DATIMID = Utils.getFacilityDATIMId();
		return startGenerateFile(request, patients, DATIMID, lastDate, currentDate);
		
	}
	
	private String startGenerateFile(HttpServletRequest request, List<Integer> filteredPatients,
									 String DATIMID,Date lastDate, Date currentDate) throws Exception {

		// Check that no export is in progress
		Map<String, Object> condition = new HashMap<>();
		condition.put("status","Processing");
		List<NDRExport> exports = nigeriaemrService.getExports(condition,1, false);
		if(exports.size() > 0 ) return "You already have an export in process, Kindly wait for it to finish";
		if(filteredPatients == null || filteredPatients.size() <= 0) return "no new patient record found";
		String contextPath = request.getContextPath();
		String fullContextPath = request.getSession().getServletContext().getRealPath(contextPath);
		UserContext userContext =  Context.getUserContext();
		Thread thread = new Thread(() -> {
			try {
				Consumer.initialize(userContext);
				ndrExtractionService.saveExport(fullContextPath,contextPath,filteredPatients,DATIMID,lastDate,currentDate);
			} catch (Exception e) {
				LoggerUtils.write(NdrFragmentController.class.getName(), e.getMessage(), LoggerUtils.LogFormat.FATAL,
						LoggerUtils.LogLevel.live);
			}
		});
		thread.start();
		Utils.updateLast_NDR_Run_Date(new Date());
		return "Export is being processed";
	}
	
	public String getAllFacilityLocation() {
		String response = "";
		try {
			response = mapper.writeValueAsString(facilityLocationService.getAllFacilityLocations());
		}
		catch (JsonProcessingException ex) {
			Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
		}
		
		return response;
	}
	
	public String getFileList() throws IOException {
		return ndrExtractionService.getFileList();
	}
	
	public boolean deleteFile(HttpServletRequest request, @RequestParam(value = "id") String id) {
		String contextPath = request.getContextPath();
		String fullContextPath = request.getSession().getServletContext().getRealPath(contextPath);
		return ndrExtractionService.deleteFile(fullContextPath, id);
	}
	
	public boolean restartFile(HttpServletRequest request, @RequestParam(value = "id") String id) {
		return ndrExtractionService.restartFile(id);
	}
	
	public void stopFile(HttpServletRequest request, @RequestParam(value = "id") String id) {
		ndrExtractionService.stopExport(id);
	}
	
	//get host for openmrs instance
	public String retrieveBiometricServer(String msg) {
		System.out.println("This is from the UI: " + msg);
		return Utils.getBiometricServer();
	}
	
	public String createFacilityLocation(@RequestParam(value = "falicityLocationString") String falicityLocationString) {
		int response = 0;
		
		try {
			FacilityLocation facilityLocation = mapper.readValue(falicityLocationString, FacilityLocation.class);
			facilityLocation.setCreator(Context.getAuthenticatedUser().toString());
			response = facilityLocationService.createFacilityLocation(facilityLocation);
			
		}
		catch (IOException ex) {
			Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
			return "Error occurred, try again";
		}
		
		if (response != -1) {
			return "Successfully created facility location";
		}
		
		return "Error occurred, try again";
		
	}
	
	public String editFacilityLocation(@RequestParam(value = "facilityLocationString") String facilityLocationString) {
		int response = 0;
		try {
			FacilityLocation facilityLocation = mapper.readValue(facilityLocationString, FacilityLocation.class);
			response = facilityLocationService.editFacilityLocation(facilityLocation);
		}
		catch (Exception ex) {
			Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
		}
		
		if (response > 0) {
			return "Successfully updated facility location";
		}
		
		return "Error occurred, try again";
	}
	
	public void deleteFacilityLocation(@RequestParam(value = "facilityLocationUUID") String facilityLocationUUID) {
		try {
			facilityLocationService.deleteFacilityLocation(facilityLocationUUID);
			
		}
		catch (Exception ex) {
			Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public String getAllLocation() {
        List<LocationModel> locationModels = new ArrayList<>();
        String locationString = "";

        try {

            Context.getLocationService().getAllLocations().stream().forEach(a -> {
                locationModels.add(new LocationModel(a.getName(), a.getLocationId()));
            });

            locationString = mapper.writeValueAsString(locationModels);
        } catch (JsonProcessingException ex) {
            Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
        }

        return locationString;
    }
	
	public String getPatientLocationAggregate() {
		String responseString = "";
		
		try {
			responseString = mapper.writeValueAsString(facilityLocationService.getPatientLocationAggregate());
		}
		catch (JsonProcessingException ex) {
			Logger.getLogger(NdrFragmentController.class.getName()).log(Level.SEVERE, null, ex);
		}
		
		return responseString;
	}
	
	public String getVersionNumber(HttpServletRequest request) {
		Version version = null;
		String response = "";
		try {
			version = Utils.getNmrsVersion();
			response = mapper.writeValueAsString(version);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return response;
	}
}
