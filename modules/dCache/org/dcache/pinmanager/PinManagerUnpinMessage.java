package org.dcache.pinmanager;

import java.util.EnumSet;

import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.Message;
import static com.google.common.base.Preconditions.*;

public class PinManagerUnpinMessage extends Message
{
    static final long serialVersionUID = 5504172816234212007L;

    private Long _pinId;
    private String _requestId;
    private PnfsId _pnfsId;

    public PinManagerUnpinMessage(PnfsId pnfsId)
    {
        checkNotNull(pnfsId);
        _pnfsId = pnfsId;
    }

    public void setPinId(long pinId)
    {
        _pinId = pinId;
    }

    public Long getPinId()
    {
        return _pinId;
    }

    public void setRequestId(String requestId)
    {
        _requestId = requestId;
    }

    public String getRequestId()
    {
        return _requestId;
    }

    public PnfsId getPnfsId()
    {
        return _pnfsId;
    }

    @Override
    public String toString()
    {
        return "PinManagerUnpinMessage[" + _requestId + "," + _pinId + "," + _pnfsId + "]";
    }
}
