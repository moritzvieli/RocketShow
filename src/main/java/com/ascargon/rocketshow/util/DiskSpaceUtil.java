package com.ascargon.rocketshow.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Returns the used and available disk space.
 *
 * @author Moritz A. Vieli
 */
public class DiskSpaceUtil {

	public static DiskSpace get() throws Exception {
		DiskSpace diskSpace = new DiskSpace();
		
		// Get the used and available space in MB
		ProcessBuilder pb = new ProcessBuilder(new String[] { "df", "-BMB" });
		Process process = pb.start();
		BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
		String line = null;

		// Loop over each line
		while ((line = br.readLine()) != null) {
			line = line.replaceAll("\t", " ");
			line = line.replaceAll(" +", " ");
			line = line.replaceAll("MB", "");
			
			String[] parts = line.split(" ");
			
			String usedMB = parts[2];
			String availableMB = parts[3];
			String drive = parts[5];
			
			if("/boot".equals(drive)) {
				diskSpace.setUsedMB(Double.parseDouble(usedMB));
				diskSpace.setAvailableMB(Double.parseDouble(availableMB));
			}
		}
		
		return diskSpace;
	}

}
