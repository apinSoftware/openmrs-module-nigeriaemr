package org.openmrs.module.nigeriaemr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.module.nigeriaemr.NDREvent;
import org.openmrs.module.nigeriaemr.NigeriaemrConfig;
import org.openmrs.module.nigeriaemr.api.service.NigeriaemrService;
import org.openmrs.module.nigeriaemr.model.DatimMap;
import org.openmrs.module.nigeriaemr.model.NDRExport;
import org.openmrs.module.nigeriaemr.model.NDRExportBatch;
import org.openmrs.module.nigeriaemr.ndrUtils.LoggerUtils;
import org.openmrs.module.nigeriaemr.ndrUtils.Utils;
import org.openmrs.module.nigeriaemr.ndrfactory.NDRExtractor;
import org.openmrs.module.nigeriaemr.util.FileUtils;
import org.openmrs.module.nigeriaemr.util.Partition;

import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.JAXBContext;
import java.io.File;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NdrExtractionService {
	
	ObjectMapper mapper = new ObjectMapper();
	
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
	
	static DecimalFormat df = new DecimalFormat("#.##");
	
	JAXBContext jaxbContext;
	
	NDRExtractor ndrExtractor;
	
	public NdrExtractionService(JAXBContext jaxbContext) {
		this.jaxbContext = jaxbContext;
		ndrExtractor = new NDRExtractor();
		Context.openSession();
		Context.setUserContext(Context.getUserContext());
		Context.openSessionWithCurrentUser();
		Context.addProxyPrivilege(NigeriaemrConfig.MODULE_PRIVILEGE);
		Context.addProxyPrivilege(NigeriaemrConfig.MODULE_PRIVILEGE);
		Context.addProxyPrivilege(NigeriaemrConfig.MODULE_PRIVILEGE);
		Context.addProxyPrivilege("Get Patients");
		Context.addProxyPrivilege("Get Observations");
		Context.addProxyPrivilege("Get Encounters");
		Context.addProxyPrivilege("Get Concepts");
		
	}
	
	public NdrExtractionService() throws Exception {
		this.jaxbContext = JAXBContext.newInstance("org.openmrs.module.nigeriaemr.model.ndr");
		this.ndrExtractor = new NDRExtractor();
	}
	
	public void saveExport(String fullContextPath, String contextPath, List<Integer> filteredPatients, String DATIMID,
	        Date lastDate, Date currentDate) throws Exception {
		
		NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
		int patientSize = filteredPatients.size();
		int batch = Utils.getBatchSize();
		
		List<List<Integer>> partitions = Partition.ofSize(filteredPatients, batch);
		String reportType = "NDR";
		String reportFolder = Utils.ensureReportFolderExist(fullContextPath, reportType);
		String formattedDate = new SimpleDateFormat("ddMMyyHHmmss").format(currentDate);
		String IPReportingState;
		String IPReportingLgaCode;
		Optional<DatimMap> datimMap = Optional.ofNullable(nigeriaemrService.getDatatimMapByDataimId(DATIMID));
		if (datimMap.isPresent()) {
			IPReportingState = datimMap.get().getStateCode().toString();
			IPReportingLgaCode = datimMap.get().getLgaCode().toString();
		} else {
			throw new Exception("Invalid datimCode configured");
		}
		
		NDRExportBatch ndrExportBatch = new NDRExportBatch();
		ndrExportBatch.setDateCreated(new Date());
		ndrExportBatch.setDateUpdated(new Date());
		ndrExportBatch.setLastExportDate(lastDate);
		ndrExportBatch.setStatus("Processing");
		ndrExportBatch.setPatients(patientSize);
		ndrExportBatch.setOwner(Context.getAuthenticatedUser());
		ndrExportBatch.setReportFolder(reportFolder);
		ndrExportBatch.setContextPath(contextPath);
		String name = IPReportingState + "_" + IPReportingLgaCode + "_" + DATIMID + formattedDate;
		ndrExportBatch.setName(name);
		nigeriaemrService.saveNdrExportBatchItem(ndrExportBatch);
		for (List<Integer> patients : partitions) {
			String fileName = IPReportingState + "_" + IPReportingLgaCode + "_" + DATIMID + "_{pepFarId}_" + formattedDate;
			// Start export process
			NDRExport ndrExport = new NDRExport();
			ndrExport.setDateStarted(currentDate);
			ndrExport.setPatients(patients.size());
			ndrExport.setOwner(Context.getAuthenticatedUser());
			ndrExport.setName(fileName);
			ndrExport.setVoided(false);
			ndrExport.setStatus("Processing");
			ndrExport.setLastDate(lastDate);
			ndrExport.setContextPath(contextPath);
			ndrExport.setReportFolder(reportFolder);
			ndrExport.setBatchId(ndrExportBatch.getId());
			try {
				ndrExport.setPatientsList(new ObjectMapper().writeValueAsString(patients));
			}
			catch (JsonProcessingException e) {
				e.printStackTrace();
				throw new Exception("Error Processing Export");
			}
			NDREvent ndrEvent = (NDREvent) ServiceContext.getInstance().getApplicationContext().getBean("ndrEvent");
			NDRExport ndrExport1 = nigeriaemrService.saveNdrExportItem(ndrExport);
			ndrEvent.send(ndrExport1);
		}
	}
	
	public void export(NDRExport ndrExport) {
		NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
		//check if batch is still valid
		NDRExportBatch ndrExportBatch = nigeriaemrService.getNDRExportBatchById(ndrExport.getBatchId());
		if (ndrExportBatch == null || !ndrExportBatch.getStatus().equalsIgnoreCase("Processing")) {
			LoggerUtils.write(NdrExtractionService.class.getName(), "skipping", LoggerUtils.LogFormat.FATAL,
			    LoggerUtils.LogLevel.live);
			return;
		}
		Context.evictFromSession(ndrExportBatch);
		
		try {
			String DATIMID = Utils.getFacilityDATIMId();
			String formattedDate = new SimpleDateFormat("ddMMyy").format(ndrExport.getDateStarted());
			String patientList = ndrExport.getPatientsList();
			List<Integer> patients = (List<Integer>) mapper.readValue(patientList, List.class);
			for (Integer patientId : patients) {
				try {
					long startTime = System.currentTimeMillis();
					
					ndrExtractor.extract(patientId, DATIMID, ndrExport.getReportFolder(), formattedDate, jaxbContext,
					    ndrExport.getLastDate(), ndrExport.getDateStarted());
					
					long endTime = System.currentTimeMillis();
					LoggerUtils.write(NdrExtractionService.class.getName(), patientId + "  " + (endTime - startTime)
					        + " milli secs : ", LoggerUtils.LogFormat.FATAL, LoggerUtils.LogLevel.live);
					
				}
				catch (Exception e) {
					e.printStackTrace();
				}
			}
			nigeriaemrService.updateStatus(ndrExport.getId(), ndrExport.getBatchId(), "Done", true);
		}
		catch (Exception ex) {
			LoggerUtils.write(NdrExtractionService.class.getName(), ex.getMessage(), LoggerUtils.LogFormat.FATAL,
			    LoggerUtils.LogLevel.live);
			nigeriaemrService.updateStatus(ndrExport.getId(), ndrExport.getBatchId(), "Failed", true);
		}
	}
	
	public String getFileList() {
		NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
        List<Map<String, Object>> fileMaps = new ArrayList<>();
        String response = "";

        try {
            List<NDRExportBatch> ndrExportBatches  = nigeriaemrService.getExportBatchByStatus(null, false);
            if (ndrExportBatches != null && ndrExportBatches.size() > 0) {
                for (NDRExportBatch ndrExportBatch : ndrExportBatches) {
                    if ("Done".equalsIgnoreCase(ndrExportBatch.getStatus())){
						ndrExportBatch.setStatus("Processing");
                    }
                    boolean active = !ndrExportBatch.getStatus().equalsIgnoreCase("Processing");
					Map<String, Object> fileMap = new HashMap<>();
                    if(ndrExportBatch.getOwner() != null){
						String owner = ndrExportBatch.getOwner().getName() == null ?  "Admin": ndrExportBatch.getOwner().getName();
						fileMap.put("owner", owner);
					}else {
						fileMap.put("owner", "unknown");
					}

                    fileMap.put("name", ndrExportBatch.getName());
                    fileMap.put("dateStarted", sdf.format(ndrExportBatch.getDateStarted()));
                    fileMap.put("total", ndrExportBatch.getPatients());
                    fileMap.put("processed", ndrExportBatch.getPatientsProcessed());
                    fileMap.put("number", ndrExportBatch.getId());
                    fileMap.put("status", ndrExportBatch.getStatus());
                    if(active){
                        if(ndrExportBatch.getPath() != null) {
                            fileMap.put("path", ndrExportBatch.getPath().replace("\\", "\\\\"));
                        }
                        fileMap.put("dateEnded", sdf.format(ndrExportBatch.getDateEnded()));
                        fileMap.put("active", true);
                    }else {
                        if(ndrExportBatch.getPatientsProcessed() != null) {
                            double progress = (ndrExportBatch.getPatientsProcessed().doubleValue() / ndrExportBatch.getPatients().doubleValue() ) * 100;
                            fileMap.put("progress", df.format(progress)  + "%");
                        }else{
                            fileMap.put("progress", "0%");
                        }
                        fileMap.put("active", false);
                        fileMap.put("dateEnded", "");
                    }

                    fileMaps.add(fileMap);
                }
            }
            response = mapper.writeValueAsString(fileMaps);
        } catch (JsonProcessingException ex) {
            Logger.getLogger(NdrExtractionService.class.getName()).log(Level.SEVERE, null, ex);
        }

        return response;
    }
	
	public boolean deleteFile(String fullContextPath, String id) {
		NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
		try {
			int idInt = Integer.parseInt(id);
			NDRExportBatch ndrExportBatch = nigeriaemrService.getNDRExportBatchById(idInt);
			if (ndrExportBatch.getPath() != null) {
				String path = ndrExportBatch.getPath();
				if (path != null) {
					String[] paths = path.split("\\\\");
					if (path.length() > 0) {
						String fileName = paths[4];
						String folder = Paths.get(new File(fullContextPath).getParentFile().toString(), "downloads", "NDR",
						    fileName).toString();
						File fileToDelete = new File(folder);
						if (fileToDelete.exists()) {
							fileToDelete.delete();
						}
					}
				}
				String reportFolder = ndrExportBatch.getReportFolder();
				if (reportFolder != null) {
					FileUtils.deleteFolder(reportFolder, true);
				}
			}
			nigeriaemrService.voidExportBatchEntry(idInt);
			nigeriaemrService.deleteExports(idInt);
			return true;
			
		}
		catch (Exception ex) {
			Logger.getLogger(NdrExtractionService.class.getName()).log(Level.SEVERE, null, ex);
			return false;
		}
	}
	
	public void stopExport(String id) {
		NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
		if (id != null) {
			int idInt = Integer.parseInt(id);
			nigeriaemrService.updateExportBatch(idInt, "Stopped", false);
		} else {
			nigeriaemrService.updateAllStatus("Stopped");
		}
	}
	
	public boolean restartFile(String id) {
		try {
			int idInt = Integer.parseInt(id);
			NigeriaemrService nigeriaemrService = Context.getService(NigeriaemrService.class);
			NDREvent ndrEvent = (NDREvent) ServiceContext.getInstance().getApplicationContext().getBean("ndrEvent");
			nigeriaemrService.updateExportBatch(idInt, "Processing", false);
			List<NDRExport> ndrExports = nigeriaemrService.getNDRExportByBatchIdByStatus(idInt, "Processing");
			for (NDRExport ndrExport : ndrExports) {
				ndrEvent.send(ndrExport);
			}
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}
}
