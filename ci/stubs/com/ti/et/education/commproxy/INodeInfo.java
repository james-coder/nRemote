package com.ti.et.education.commproxy;
import com.ti.et.education.commproxy.info.INodeSWVersionsInfo;
public interface INodeInfo {
    String getName();
    String getSerialNumber();
    INodeSWVersionsInfo getNodeSWVersionsInfo();
}
