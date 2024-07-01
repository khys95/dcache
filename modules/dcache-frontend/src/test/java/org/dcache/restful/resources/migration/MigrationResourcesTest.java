package org.dcache.restful.resources.bulk;

import static org.dcache.restful.resources.bulk.BulkResources.toBulkRequest;
import static org.junit.Assert.assertEquals;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsHandler;
import diskCacheV111.vehicles.PnfsMessage;
import dmg.cells.nucleus.CellPath;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.BadRequestException;
import org.dcache.services.bulk.BulkRequest;
import org.dcache.services.bulk.BulkRequest.Depth;
import org.dcache.vehicles.PnfsResolveSymlinksMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MigrationResourcesTest {



}