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
        LOGGER.log(Level.INFO, "Node: " + node.getDisplayName());
        LOGGER.log(Level.INFO, "Task: " + task.getFullDisplayName());
        
        VirtualMachineSlave slave = (VirtualMachineSlave) node;
        if (null == slave) {
            LOGGER.log(Level.SEVERE, "convert node to slave failed");
        }
        
        ComputerLauncher launcher = slave.getLauncher();
        
        if (launcher instanceof VirtualMachineLauncher) {

            VirtualMachineLauncher slaveLauncher = (VirtualMachineLauncher) launcher;
            String vmName = slaveLauncher.getVirtualMachineName();
             String snapshotName = slave.getSnapshotName();

            LOGGER.log(Level.INFO, "Preparing to revert " + vmName + " to snapshot " + snapshotName + ".");

            Hypervisor hypervisor = null;
            try {
                hypervisor = slaveLauncher.findOurHypervisorInstance();
            } catch (VirtException e) {
                LOGGER.log(Level.SEVERE, "reverting " + vmName + " to " + snapshotName + " failed: " + e.getMessage());
                return;
            }
                    

            try {
                Map<String, IDomain> domains = hypervisor.getDomains();
                IDomain domain = domains.get(vmName);

                if (domain != null) {
                    try {
                        IDomainSnapshot snapshot = domain.snapshotLookupByName(snapshotName);
                        try {
                            Computer computer = slave.getComputer();
                            try {
                                computer.getChannel().syncLocalIO();
                                try {
                                    computer.getChannel().close();
                                    computer.disconnect(new OfflineCause.ByCLI("Stopping " + vmName + " to revert to snapshot " + snapshotName + "."));
                                    try {
                                        computer.waitUntilOffline();

                                        LOGGER.log(Level.INFO, "Reverting " + vmName + " to snapshot " + snapshotName + ".");
                                        domain.revertToSnapshot(snapshot);

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
                        } catch (VirtException e) {
                            LOGGER.log(Level.SEVERE, "No snapshot named " + snapshotName + " for VM: " + e);
                        }
                    } catch (VirtException e) {
                        LOGGER.log(Level.SEVERE, "No snapshot named " + snapshotName + " for VM: " + e);
                    }
                } else {
                    LOGGER.log(Level.SEVERE, "No VM named " + vmName);
                }
            } catch (VirtException e) {
                LOGGER.log(Level.SEVERE, "Can't get VM domains: " + e);
            }
        }
        else {
            LOGGER.log(Level.WARNING, "Node is not a VirtualMachineSlave");
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
      

        return super.disconnect(cause);
    }

}
