import com.ti.et.education.commproxy.INodeID;
import com.ti.et.education.commproxy.INodeInfo;

/**
 * @author Levak
 */
class CalcItem {
    public INodeID nodeID = null;
    public String SID = "";

    public CalcItem(INodeID nodeID) {
        this.nodeID = nodeID;
        // getDeviceInfo returns null when the handheld drops mid-refresh
        INodeInfo info = Remote.getDeviceInfo(nodeID);
        this.SID = (info != null) ? info.getSerialNumber() : "unknown";
    }

    @Override
    public String toString() {
        return this.SID;
    }
}