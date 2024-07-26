package org.dcache.restful.resources.migration;

import lombok.NoArgsConstructor;

import java.util.ArrayList;

@NoArgsConstructor
public class MigrationRequest {

    public String sourcePool;
    public ArrayList<String> targetPool;
    public int concurrency;
    public String pins;
    public String smode;
    public String tmode;
    public boolean verify;
    public boolean eager;
    public ArrayList<String> exclude;
    public ArrayList<String> include;
    public int refresh;
    public String select;
    public String target;
    public FileAttributes fileAttributes;

    public String getSourcePool() {return sourcePool;}
}


