/**
 *  Copyright (C) 2010, Byte-Code srl <http://www.byte-code.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Date: Mar 04, 2010
 * @author Marco Mornati<mmornati@byte-code.com>
 */

package hudson.plugins.libvirt;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.plugins.libvirt.lib.IDomain;
import hudson.plugins.libvirt.lib.IDomainSnapshot;
import hudson.plugins.libvirt.lib.VirtException;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;
import hudson.util.io.RewindableRotatingFileOutputStream;
import java.io.IOException;

public class VirtualMachineSlaveComputer extends SlaveComputer {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlaveComputer.class.getName());

    private final TaskListener taskListener;

    public VirtualMachineSlaveComputer(Slave slave) {
        super(slave);
        this.taskListener = new StreamTaskListener(new RewindableRotatingFileOutputStream(getLogFile(), 10));
    }
    
    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        LOGGER.log(Level.INFO, "taskAccepted called");
        Node node = executor.getOwner().getNode();
        LOGGER.log(Level.INFO, "Node: {0}", node.getDisplayName());
        LOGGER.log(Level.INFO, "Task: {0}", task.getFullDisplayName());
        
        VirtualMachineSlave slave = (VirtualMachineSlave) node;
        
        ComputerLauncher launcher = slave.getLauncher();
        VirtualMachineLauncher slaveLauncher = (VirtualMachineLauncher) launcher;
        String vmName = slaveLauncher.getVirtualMachineName();
        String snapshotName = slave.getSnapshotName();
        Computer computer = slave.getComputer();
        try {
            computer.getChannel().syncLocalIO();
            try {
                computer.getChannel().close();
                computer.disconnect(new OfflineCause.ByCLI("Stopping " + vmName + " to revert to snapshot " + snapshotName + "."));
                try {
                    computer.waitUntilOffline();

                    LOGGER.log(Level.INFO, "Relaunching " + vmName + ".");
                    try {
                        launcher.launch(slave.getComputer(), this.taskListener);
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "Could not relaunch VM: " + e);
                    } catch (InterruptedException e) {
                        LOGGER.log(Level.SEVERE, "Could not relaunch VM: " + e);
                    } catch (NullPointerException e) {
                        LOGGER.log(Level.SEVERE, "Could not determine node.");
                    }
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Interrupted while waiting for computer to be offline: " + e);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Error closing channel: " + e);
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Interrupted while syncing IO: " + e);
        } catch (NullPointerException e) {
            LOGGER.log(Level.SEVERE, "Could not determine channel.");
        }
    }

    // since we will revert on taskAccepted, we don't need this anymore.
    @Override
    public Future<?> disconnect(OfflineCause cause) {
        String reason = "unknown";
        if (cause != null) {
            reason =  "reason: " + cause + " (" + cause.getClass().getName() + ")";
        }

        VirtualMachineSlave slave = (VirtualMachineSlave) getNode();
        if (null == slave) {
            taskListener.getLogger().println("disconnect from undefined agent reason: " + reason);
            LOGGER.log(Level.SEVERE, "disconnect from null agent reason: " + reason);
            return super.disconnect(cause);
        }
        String virtualMachineName = slave.getVirtualMachineName();
        
        LOGGER.log(Level.INFO, "disconnect from agent: " + virtualMachineName + " reason: " + reason);
        
        VirtualMachineLauncher vmL = (VirtualMachineLauncher) getLauncher();
        Hypervisor hypervisor;
        try {
            hypervisor = vmL.findOurHypervisorInstance();
        } catch (VirtException e) {
            taskListener.getLogger().println(e.getMessage());
            LOGGER.log(Level.SEVERE, "cannot find hypervisor instance on disconnect" + e.getMessage());
            return super.disconnect(cause);
        }

        LOGGER.log(Level.INFO, "Virtual machine \""  + virtualMachineName + "\" (agent \"" + getDisplayName() + "\") is to be shut down." + reason);
        taskListener.getLogger().println("Virtual machine \"" + virtualMachineName + "\" (agent \"" + getDisplayName() + "\") is to be shut down.");
        try {
            Map<String, IDomain> computers = hypervisor.getDomains();
            IDomain domain = computers.get(virtualMachineName);
            if (domain != null) {
                if (domain.isRunningOrBlocked()) {
                   
                    taskListener.getLogger().println("Shutting down.");
                    System.err.println("method: " + slave.getShutdownMethod());
                    if (slave.getShutdownMethod().equals("suspend")) {
                        domain.suspend();
                    } else if (slave.getShutdownMethod().equals("destroy")) {
                        domain.destroy();
                    } else {
                        domain.shutdown();
                    }
                    
                } else {
                    taskListener.getLogger().println("Already suspended, no shutdown required.");
                }
                Hypervisor vmC = vmL.findOurHypervisorInstance();
                vmC.markVMOffline(getDisplayName(), vmL.getVirtualMachineName());
            } else {
                // log to agent
                taskListener.getLogger().println("\"" + virtualMachineName + "\" not found on Hypervisor, can not shut down!");

                // log to jenkins
                LogRecord rec = new LogRecord(Level.WARNING, "Can not shut down {0} on Hypervisor {1}, domain not found!");
                rec.setParameters(new Object[]{virtualMachineName, hypervisor.getHypervisorURI()});
                LOGGER.log(rec);
            }
        } catch (VirtException t) {
            taskListener.fatalError(t.getMessage(), t);

            LogRecord rec = new LogRecord(Level.SEVERE, "Error while shutting down {0} on Hypervisor {1}.");
            rec.setParameters(new Object[]{slave.getVirtualMachineName(), hypervisor.getHypervisorURI()});
            rec.setThrown(t);
            LOGGER.log(rec);
        }

        return super.disconnect(cause);
    }

}
