package org.dcache.restful.providers.migrations;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ApiModel(description = "Container for migration information regarding a pool.")
public class MigrationInfo {
    private static final long serialVersionUID = -7942464845703567335L;

    @ApiModelProperty("Current state of the migration.")
    private String state;

    @ApiModelProperty("Number of queued PNFSIDs.")
    private Integer queued;

    @ApiModelProperty("Number of attempts.")
    private Integer attempts;

    @ApiModelProperty("List of target pools for the migration.")
    private List<String> targetPools;

    @ApiModelProperty("Number of completed files, bytes and a percentage (if not finished or failed). (X files; Y bytes; Z%)")
    private String completed;

    @ApiModelProperty("Number of total bytes.")
    private Integer total;

    @ApiModelProperty("Representation of the running tasks for the migration job.")
    private String runningTasks;

    @ApiModelProperty("Representation of the most recent errors for the migration job.")
    private String mostRecentErrors;
}
