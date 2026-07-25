package com.ti.et.navnetcommproxy;
import com.ti.eps.navnet.NodeHandle;
import com.ti.et.education.commproxy.*;
public class NavNetCommProxy {
    public static synchronized NavNetCommProxy init(String a, String b, int c, int d, String e, String f) throws Exception { return null; }
    public static synchronized NavNetCommProxy getInstance() { return null; }
    public INodeID[] getConnectedNodes() { return null; }
    public INodeInfo getNodeInfo(INodeID id) throws Exception { return null; }
    public NodeHandle getHandle(INodeID id) { return null; }
    public ICommproxyNodeScreen getScreen(INodeID id, boolean color) throws Exception { return null; }
    public void sendFileToNode(INodeID id, String src, String dst) throws Exception {}
    public void sendFileToNode(INodeID id, String src, String dst, IProgressListener l) throws Exception {}
}
