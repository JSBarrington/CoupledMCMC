package coupledMCMC;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import beast.base.core.BEASTObject;
import beast.base.inference.StateNode;

import beast.base.core.Description;
import beast.base.inference.Logger;

@Description("Logger for MC3 that can be suppressed (useful for heated chains)")
public class CoupledLogger extends Logger {
	
	public CoupledLogger(Logger other) {
		loggersInput.setValue(other.loggersInput.get(), this);
		fileNameInput.setValue(other.fileNameInput.get(), this);
		everyInput.setValue(other.everyInput.get(), this);
		modelInput.setValue(other.modelInput.get(), this);
		modeInput.setValue(other.modeInput.get(), this);
		sortModeInput.setValue(other.sortModeInput.get(), this);
		sanitiseHeadersInput.setValue(other.sanitiseHeadersInput.get(), this);
	}

	public CoupledLogger() {
	}
	
    // Logger's offset is global; the multi-chain controller owns its restart coordinate.
    static void setManagedSampleOffset(long offset) {
        sampleOffset = offset;
    }

    static LOGMODE restartLogMode(Logger source) {
        LOGMODE mode = source.modeInput.get();
        if (mode != LOGMODE.autodetect) return mode;
        List<BEASTObject> payload = source.loggersInput.get();
        return payload.size() == 1 && payload.get(0) instanceof StateNode
                && payload.get(0).getClass().getName().contains("Tree") ? LOGMODE.tree : LOGMODE.compound;
    }

    /** Read-only filename projection after the existing XML producer has assigned payload IDs. */
    static String restartFileName(Logger source, int physical) throws IOException {
        String name = source.fileNameInput.get();
        if (name == null) return null; // initRun does not copy these loggers.
        if (physical > 0) name = "chain" + physical + name;
        if (name.isEmpty()) return null; // Logger tests stdout before applying file.name.prefix.
        if (name.contains("$(tree)")) {
            List<BEASTObject> ordered = new ArrayList<>(source.loggersInput.get());
            if (restartLogMode(source) == LOGMODE.compound) {
                if (source.sortModeInput.get() == SORTMODE.alphabetic) {
                    ordered.sort((a, b) -> a.getID() == null || b.getID() == null
                            ? 0 : a.getID().compareTo(b.getID()));
                } else if (source.sortModeInput.get() == SORTMODE.smart) {
                    // The inherited smart ordering groups by first-seen root, then sorts suffixes.
                    LinkedHashMap<String, List<BEASTObject>> groups = new LinkedHashMap<>();
                    for (BEASTObject object : ordered) {
                        String id = object.getID() == null ? "" : object.getID();
                        int dot = id.indexOf('.');
                        String root = dot > 0 ? id.substring(0, dot) : id;
                        groups.computeIfAbsent(root, ignored -> new ArrayList<>()).add(object);
                    }
                    ordered.clear();
                    for (List<BEASTObject> group : groups.values()) {
                        group.sort((a, b) -> smartSuffix(a).compareTo(smartSuffix(b)));
                        ordered.addAll(group);
                    }
                }
            }
            String tree = "tree";
            for (BEASTObject object : ordered) {
                String id = object.getID();
                if (id != null && id.indexOf(".t:") > 0) tree = id.substring(id.indexOf(".t:") + 3);
            }
            name = name.replace("$(tree)", tree);
        }
        String prefix = System.getProperty("file.name.prefix");
        return prefix == null ? name : prefix + name;
    }

    private static String smartSuffix(BEASTObject object) {
        String id = object.getID();
        return id != null && id.indexOf('.') > 0 ? id.substring(id.indexOf('.') + 1) : "";
    }

	boolean suppressLogging = false;
	
    public void setM_out(PrintStream m_out_alt) {
    	m_out = m_out_alt;  
    }
	
    public void setSuppressLogging(boolean suppressLogging) {
    	this.suppressLogging = suppressLogging;  
    }
    
    public boolean getSuppressLogging() {
    	return suppressLogging;  
    }

    @Override
    public void init() throws IOException{
    	if (!suppressLogging){
    		super.init();
    	}
    }

    
    
//    @Override
//	protected boolean openLogFile() throws IOException {
//    	if (!suppressLogging)
//    		super.openLogFile();
//    	
//    	return false;
//	}
    
    @Override
    public void log(long sampleNr){
    	if (!suppressLogging)
    		super.log(sampleNr);
    }
    
    @Override
    public void close() {
    	if (!suppressLogging)
    		super.close();
    }
}
