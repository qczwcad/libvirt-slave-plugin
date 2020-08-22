package hudson.plugins.libvirt;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.plugins.libvirt.lib.IDomain;
import hudson.plugins.libvirt.lib.IDomainSnapshot;
import hudson.plugins.libvirt.lib.VirtException;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;


@Extension
public class LibvirtSnapshotRevertRunListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(LibvirtSnapshotRevertRunListener.class.getName());
   
    @Override
    public void onStarted(Run<?, ?> r, TaskListener listener) {
        LOGGER.log(Level.INFO, "onStarted");
        Executor executor = r.getExecutor();
        LOGGER.log(Level.INFO, "getExecutor returned");

        if (executor == null) {
            LOGGER.log(Level.WARNING, "executor is null");
            return;
        }

        Node node = executor.getOwner().getNode();
        LOGGER.log(Level.INFO, "Node: " + node.getDisplayName()); 
   
    }

    private static void revertVMSnapshot(VirtualMachineSlave slave, String snapshotName, TaskListener listener) {
        LOGGER.log(Level.INFO, "revertVMSnapshot");
        ComputerLauncher launcher = slave.getLauncher();
        if (launcher instanceof VirtualMachineLauncher) {

            VirtualMachineLauncher slaveLauncher = (VirtualMachineLauncher) launcher;
            String vmName = slaveLauncher.getVirtualMachineName();

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
                                            launcher.launch(slave.getComputer(), listener);
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
    }
}
