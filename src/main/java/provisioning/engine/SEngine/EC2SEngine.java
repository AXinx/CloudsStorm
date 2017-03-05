package provisioning.engine.SEngine;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import provisioning.credential.Credential;
import provisioning.credential.EC2Credential;
import provisioning.database.Database;
import provisioning.database.EC2.EC2Database;
import provisioning.engine.VEngine.EC2.EC2Agent;
import provisioning.engine.VEngine.EC2.EC2VEngine_createSubnet;
import topologyAnalysis.dataStructure.SubTopology;
import topologyAnalysis.dataStructure.SubTopologyInfo;
import topologyAnalysis.dataStructure.Subnet;
import topologyAnalysis.dataStructure.EC2.EC2SubTopology;
import topologyAnalysis.dataStructure.EC2.EC2Subnet;
import topologyAnalysis.dataStructure.EC2.EC2VM;

public class EC2SEngine extends SEngine implements SEngineCoreMethod{
	
	private static final Logger logger = Logger.getLogger(EC2SEngine.class);

	private EC2Agent ec2Agent;
	
	private boolean setEC2Agent(EC2Credential ec2Credential){
		if(ec2Agent != null){
			logger.warn("The ec2Agent has been initid!");
			return false;
		}
		ec2Agent = new EC2Agent(ec2Credential.accessKey, ec2Credential.secretKey);
		if(ec2Agent != null)
			return true;
		else{
			logger.error("The ec2client cannot be initialized!");
			return false;
		}
	}
	
	@Override
	public boolean provision(SubTopologyInfo subTopologyInfo, Credential credential, Database database) {
		EC2SubTopology ec2SubTopology = (EC2SubTopology)subTopologyInfo.subTopology;
		EC2Credential ec2Credential = (EC2Credential)credential;
		if(!subTopologyInfo.status.trim().toLowerCase().equals("fresh")){
			logger.warn("The sub-topology '"+subTopologyInfo.topology+"' has ever been provisioned!");
			return false;
		}
		
		if(ec2Agent == null){
			if(!setEC2Agent(ec2Credential))
				return false;
		}
		ec2Agent.setEndpoint(subTopologyInfo.endpoint);
		
		////Create the vpc for all the VM without a subnet definition
		String vpcId4all = ec2Agent.createVPC("192.168.0.0/16");
		if(vpcId4all == null){
			logger.error("Error happens during creating VPC for sub-topology "+subTopologyInfo.topology );
			return false;
		}
		///Define Subnet for these VMs.
		ArrayList<EC2Subnet> actualSubnets = new ArrayList<EC2Subnet>();
		int subnetIndex = -1;
		for(int vi = 0 ; vi<ec2SubTopology.components.size() ; vi++){
			EC2VM curVM = ec2SubTopology.components.get(vi);
			//if the vm does not belong to a subnet
			if(getSubnet(curVM) == null){
				subnetIndex++;
				EC2Subnet curEC2Subnet = new EC2Subnet();
				curEC2Subnet.org_subnet = new Subnet();
				curEC2Subnet.org_subnet.netmask = "24";
				curEC2Subnet.org_subnet.subnet = "192.168."+subnetIndex+".0";
				curEC2Subnet.vpcId = vpcId4all;
				curVM.subnetAllInfo = curEC2Subnet;
				actualSubnets.add(curEC2Subnet);
			}
		}
		for(int si = 0 ; si<ec2SubTopology.subnets.size() ; si++){
			Subnet curSubnet = ec2SubTopology.subnets.get(si);
			EC2Subnet curEC2Subnet = new EC2Subnet();
			curEC2Subnet.org_subnet = curSubnet;
			int VMinSubnet = 0;
			for(int vi = 0 ; vi<ec2SubTopology.components.size() ; vi++){
				EC2VM curVM = ec2SubTopology.components.get(vi);
				Subnet belongingSubnet = null;
				if((belongingSubnet = getSubnet(curVM)) != null){
					if(belongingSubnet.name.equals(curSubnet.name)){
						VMinSubnet++;
						curVM.actualPrivateAddress = getPrivateAddress(curVM);
						curVM.subnetAllInfo = curEC2Subnet;
					}
				}
			}
			if(VMinSubnet != 0)
				actualSubnets.add(curEC2Subnet);
		}
		
		////Create all the subnets using multi threads
		int poolSize = actualSubnets.size();
		ExecutorService executor4subnet = Executors.newFixedThreadPool(poolSize);
		for(int si = 0 ; si < actualSubnets.size() ; si++){
			EC2VEngine_createSubnet ec2createSubnet = new EC2VEngine_createSubnet(
					ec2Agent, actualSubnets.get(si));
			executor4subnet.execute(ec2createSubnet);
		}
		executor4subnet.shutdown();
		try {
			int count = 0;
			while (!executor4subnet.awaitTermination(1, TimeUnit.SECONDS)){
				count++;
				if(count > 100){
					logger.error("Unknown error! Some subnet cannot be set up!");
					return false;
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			logger.error("Unexpected error!");
			return false;
		}
		
		logger.debug("All the subnets have been created!");
		
		return true;
	}
	
	//To test whether the VM belongs to a subnet.
	//If not, then return null.
	private Subnet getSubnet(EC2VM curVM){
		for(int ei = 0 ; ei<curVM.ethernetPort.size() ; ei++){
			if(curVM.ethernetPort.get(ei).subnet != null)
				return curVM.ethernetPort.get(ei).subnet;
		}
		return null;
	}
	
	//To get the actual private address of the VM, if the VM belongs to a subnet.
	//If not, then return null.
	private String getPrivateAddress(EC2VM curVM){
		for(int ei = 0 ; ei<curVM.ethernetPort.size() ; ei++){
			if(curVM.ethernetPort.get(ei).subnet != null)
				return curVM.ethernetPort.get(ei).address;
		}
		return null;
	}
	

	@Override
	public boolean stop(SubTopology subTopology, Credential credential, Database database) {
		
		return false;
	}

	/**
	 * Update the AMI information.
	 */
	@Override
	public boolean runtimeCheckandUpdate(SubTopologyInfo subTopologyInfo,
			Database database) {
		
		///Update the endpoint information
		EC2Database ec2Database = (EC2Database)database;
		EC2SubTopology ec2SubTopology = (EC2SubTopology)subTopologyInfo.subTopology;
		String domain = subTopologyInfo.domain.trim().toLowerCase();
		if((subTopologyInfo.endpoint = ec2Database.domain_endpoint.get(domain)) == null){
			logger.error("Domain '"+domain+"' of sub-topology '"+subTopologyInfo.topology+"' cannot be mapped into some EC2 endpoint!");
			return false;
		}
		
		for(int vi = 0 ; vi < ec2SubTopology.components.size() ; vi++){
			EC2VM curVM = ec2SubTopology.components.get(vi);
			if((curVM.AMI = ec2Database.getAMI(curVM.OStype, domain)) == null){
				logger.error("The EC2 AMI of 'OStype' '"+curVM.OStype+"' in domain '"+domain+"' is not known!");
				return false;
			}
		}
		
		return true;
	}

}