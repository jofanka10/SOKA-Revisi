import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicyFirstFit;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BatchRunner {
    // [KONFIGURASI] DATACENTER & HOST
    //   kolom: hostCount | hostPes | hostMips | hostRam | hostBw | hostStorage
    static final DatacenterSpec[] DC_SPECS = {
        new DatacenterSpec( 2,          8,        100_000,  32_000,  1_000_000,  1_000_000),
        new DatacenterSpec( 3,          4,         50_000,  16_000,  1_000_000,  1_000_000),
    };

    // [KONFIGURASI] VM (1 baris = 1 VM)
    //   kolom: mips  | vCpu | ram   | bw     | size
    static final VmSpec[] VM_SPECS = {
        new VmSpec(     1_000,   2,    4_096,  10_000,  10_000),  // VM-0 Small
        new VmSpec(     2_500,   2,    4_096,  10_000,  10_000),  // VM-1 Medium
        new VmSpec(     5_000,   2,    4_096,  10_000,  10_000),  // VM-2 Large
        new VmSpec(     7_500,   4,    4_096,  10_000,  10_000),  // VM-3 XLarge
    };

    // [KONFIGURASI] BATAS PENGUJIAN JUMLAH TASK (n)
    static final int MIN_N  = 9_000;
    static final int MAX_N  = 10_000;
    static final int STEP_N = 100;

    static class DatacenterSpec {
        final int hostCount, hostPes;
        final double hostMips;
        final long hostRam, hostBw, hostStorage;

        DatacenterSpec(int hostCount, int hostPes, double hostMips,
                       long hostRam, long hostBw, long hostStorage) {
            this.hostCount = hostCount;
            this.hostPes = hostPes;
            this.hostMips = hostMips;
            this.hostRam = hostRam;
            this.hostBw = hostBw;
            this.hostStorage = hostStorage;
        }
    }

    static class VmSpec {
        final double mips;
        final int vCpu;
        final long ram, bw, size;

        VmSpec(double mips, int vCpu, long ram, long bw, long size) {
            this.mips = mips;
            this.vCpu = vCpu;
            this.ram = ram;
            this.bw = bw;
            this.size = size;
        }
    }

    public static void main(String[] args) throws IOException {
        validateInfrastructure();
        String dataDir = args.length > 0 ? args[0] : "Dataset-Sintetik";
        String outFile = args.length > 1 ? args[1] : "results.csv";

        List<String> csvFiles = List.of(
                Paths.get(dataDir, "synthetic_seed1.csv").toString(),
                Paths.get(dataDir, "synthetic_seed2.csv").toString(),
                Paths.get(dataDir, "synthetic_seed3.csv").toString());
        List<int[]> runPlan = buildRunPlan(csvFiles.size());

        try (PrintWriter writer = new PrintWriter(new FileWriter(outFile))) {
            writer.println("algorithm,n_tasks,rep,dataset_file,makespan,degree_of_imbalance,utilization,throughput,cloudlet_success,total_vm_mips");
            for (int[] plan : runPlan) {
                for (String algo : new String[]{"MCT", "FCFS", "MINMIN"}) {
                    String row = runOnce(algo, plan[0], csvFiles.get(plan[1]));
                    if (row != null) writer.println(row);
                }
                writer.flush();
                System.out.printf("[PROGRES] selesai n=%d rep=%d%n", plan[0], plan[1] + 1);
            }
        }
        System.out.println("[SELESAI] Hasil lengkap tersimpan di " + outFile);
    }

    static List<int[]> buildRunPlan(int numSeeds) {
        List<int[]> plan = new ArrayList<>();
        for (int rep = 0; rep < 3; rep++) {
            for (int n = MIN_N; n <= MAX_N; n += STEP_N) {
                plan.add(new int[]{n, rep % numSeeds});
            }
        }
        return plan;
    }

    /** Cek kapasitas sekali di awal (urutan sama dengan mapper: DC = indeks VM % jumlah DC, lalu fallback). */
    static void validateInfrastructure() {
        if (DC_SPECS.length == 0 || VM_SPECS.length == 0) {
            throw new IllegalStateException("Minimal harus ada satu datacenter dan satu VM.");
        }

        long[][][] free = new long[DC_SPECS.length][][]; // [dc][host]{pe, ram, bw, storage}
        for (int d = 0; d < DC_SPECS.length; d++) {
            DatacenterSpec dc = DC_SPECS[d];
            if (dc.hostCount <= 0 || dc.hostPes <= 0 || dc.hostMips <= 0
                    || dc.hostRam <= 0 || dc.hostBw <= 0 || dc.hostStorage <= 0) {
                throw new IllegalStateException("Spesifikasi DC " + (d + 1) + " harus positif.");
            }
            free[d] = new long[dc.hostCount][];
            for (int h = 0; h < dc.hostCount; h++) {
                free[d][h] = new long[]{dc.hostPes, dc.hostRam, dc.hostBw, dc.hostStorage};
            }
        }

        for (int i = 0; i < VM_SPECS.length; i++) {
            VmSpec vm = VM_SPECS[i];
            if (vm.mips <= 0 || vm.vCpu <= 0 || vm.ram <= 0 || vm.bw <= 0 || vm.size <= 0) {
                throw new IllegalStateException("Spesifikasi VM-" + i + " harus positif.");
            }

            boolean allocated = false;
            for (int attempt = 0; attempt < DC_SPECS.length && !allocated; attempt++) {
                int d = (i % DC_SPECS.length + attempt) % DC_SPECS.length;
                if (DC_SPECS[d].hostMips < vm.mips) continue;

                for (long[] host : free[d]) {
                    if (host[0] >= vm.vCpu && host[1] >= vm.ram && host[2] >= vm.bw && host[3] >= vm.size) {
                        host[0] -= vm.vCpu;
                        host[1] -= vm.ram;
                        host[2] -= vm.bw;
                        host[3] -= vm.size;
                        allocated = true;
                        break;
                    }
                }
            }
            if (!allocated) {
                throw new IllegalStateException("VM-" + i
                        + " tidak muat di datacenter mana pun; periksa PE, MIPS per PE, RAM, BW, dan storage.");
            }
        }
    }

    static List<Host> buatHostList(DatacenterSpec spec) {
        List<Host> hostList = new ArrayList<>();
        for (int h = 0; h < spec.hostCount; h++) {
            List<Pe> peList = new ArrayList<>();
            for (int j = 0; j < spec.hostPes; j++) {
                peList.add(new PeSimple(spec.hostMips));
            }
            hostList.add(new HostSimple(spec.hostRam, spec.hostBw, spec.hostStorage, peList));
        }
        return hostList;
    }

    static List<Vm> buatVmList() {
        List<Vm> vmList = new ArrayList<>();
        for (VmSpec spec : VM_SPECS) {
            Vm vm = new VmSimple(spec.mips, spec.vCpu);
            vm.setRam(spec.ram).setBw(spec.bw).setSize(spec.size);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }
        return vmList;
    }

    static String runOnce(String algo, int n, String csvFile) {
        List<Long> lengths = readCsv(csvFile, n);
        if (lengths.isEmpty()) {
            System.out.println("[ERROR] CSV kosong/gagal dibaca: " + csvFile);
            return null;
        }

        CloudSim simulation = new CloudSim();

        List<Datacenter> datacenters = new ArrayList<>();
        for (DatacenterSpec spec : DC_SPECS) {
            datacenters.add(new DatacenterSimple(simulation, buatHostList(spec), new VmAllocationPolicyFirstFit()));
        }

        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = buatVmList();
        Map<Long, Integer> dcAttempts = new HashMap<>();
        Map<Long, Integer> vmIndexes = new HashMap<>();
        broker.setDatacenterMapper((lastDatacenter, vm) -> {
            int attempt = dcAttempts.getOrDefault(vm.getId(), 0);
            if (attempt >= datacenters.size()) return Datacenter.NULL;

            int preferred = vmIndexes.get(vm.getId()) % datacenters.size();
            Datacenter target = datacenters.get((preferred + attempt) % datacenters.size());
            if (attempt > 0) {
                System.out.printf("[FALLBACK] VM %d tidak muat di DC sebelumnya; mencoba DC %d%n",
                        vm.getId(), target.getId());
            }
            dcAttempts.put(vm.getId(), attempt + 1);
            return target;
        });

        boolean[] vmAllocated = new boolean[vmList.size()];
        boolean[] datacenterUsed = new boolean[datacenters.size()];
        for (int i = 0; i < vmList.size(); i++) {
            int vmIndex = i;
            vmList.get(i).addOnHostAllocationListener(info -> {
                vmAllocated[vmIndex] = true;
                int dcIndex = datacenters.indexOf(info.getHost().getDatacenter());
                if (dcIndex >= 0) datacenterUsed[dcIndex] = true;
            });
        }
        broker.submitVmList(vmList);
        for (int i = 0; i < vmList.size(); i++) {
            vmIndexes.put(vmList.get(i).getId(), i);
        }

        List<Cloudlet> cloudletList = new ArrayList<>();
        for (long len : lengths) {
            cloudletList.add(new CloudletSimple(len, 1).setUtilizationModelCpu(new UtilizationModelFull()));
        }

        // ==== Penjadwalan: mapping task->VM/PE di luar CloudSim ====
        double[][] readyByPe = new double[vmList.size()][];
        double[] vmBusy = new double[vmList.size()];
        for (int i = 0; i < vmList.size(); i++) {
            readyByPe[i] = new double[(int) vmList.get(i).getNumberOfPes()];
        }
        switch (algo) {
            case "FCFS" -> fcfsAssign(cloudletList, vmList, readyByPe, vmBusy);
            case "MCT" -> mctAssign(cloudletList, vmList, readyByPe, vmBusy);
            case "MINMIN" -> minMinAssign(cloudletList, vmList, readyByPe, vmBusy);
            default -> throw new IllegalArgumentException("Algoritma tidak dikenal: " + algo);
        }

        // RAM/BW dibagi rata per PE VM tujuan agar task paralel tidak kehabisan
        for (Cloudlet c : cloudletList) {
            double share = 0.99 / c.getVm().getNumberOfPes();
            c.setUtilizationModelRam(new UtilizationModelDynamic(share));
            c.setUtilizationModelBw(new UtilizationModelDynamic(share));
        }

        broker.submitCloudletList(cloudletList);
        simulation.start();

        for (boolean allocated : vmAllocated) {
            if (!allocated) {
                System.out.printf("[PERINGATAN] VM tidak berhasil dialokasikan (algo=%s, n=%d)%n", algo, n);
                break;
            }
        }
        for (int i = 0; i < datacenterUsed.length; i++) {
            if (!datacenterUsed[i]) {
                System.out.printf("[PERINGATAN] Datacenter %d tidak menerima VM (algo=%s, n=%d)%n",
                        datacenters.get(i).getId(), algo, n);
            }
        }

        List<Cloudlet> finished = broker.getCloudletFinishedList();
        if (finished.size() != cloudletList.size()) {
            throw new IllegalStateException(String.format(
                    "Simulasi %s n=%d: hanya %d dari %d cloudlet yang selesai.",
                    algo, n, finished.size(), cloudletList.size()));
        }

        // ==== Metrik ====
        double makespan = finished.stream().mapToDouble(Cloudlet::getFinishTime).max().orElse(0);
        double busySum = finished.stream().mapToDouble(Cloudlet::getActualCpuTime).sum();

        // DI = (max T_j - min T_j) / mean T_j, T_j = total waktu CPU aktif VM j (semua PE)
        double tMax = Arrays.stream(vmBusy).max().orElse(0);
        double tMin = Arrays.stream(vmBusy).min().orElse(0);
        double tAvg = Arrays.stream(vmBusy).average().orElse(0);
        double di = tAvg > 0 ? (tMax - tMin) / tAvg : 0;

        long totalPes = vmList.stream().mapToLong(Vm::getNumberOfPes).sum();
        double utilization = makespan > 0 ? busySum / (totalPes * makespan) : 0;
        double throughput = makespan > 0 ? finished.size() / makespan : 0;
        double totalVmMips = vmList.stream().mapToDouble(Vm::getTotalMipsCapacity).sum();

        return String.format(Locale.US, "%s,%d,%d,%s,%.2f,%.4f,%.4f,%.6f,%d,%.2f",
                algo, n, planRep(csvFile), Paths.get(csvFile).getFileName(),
                makespan, di, utilization, throughput, finished.size(), totalVmMips);
    }

    /** Indeks PE dengan ready time paling kecil di satu VM. */
    static int earliestPe(double[] pes) {
        int best = 0;
        for (int p = 1; p < pes.length; p++) {
            if (pes[p] < pes[best]) best = p;
        }
        return best;
    }

    /** FCFS: task diberikan ke PE yang paling cepat tersedia. */
    static void fcfsAssign(List<Cloudlet> cloudlets, List<Vm> vms, double[][] readyByPe, double[] vmBusy) {
        for (Cloudlet c : cloudlets) {
            int bestVm = 0, bestPe = earliestPe(readyByPe[0]);
            for (int j = 1; j < vms.size(); j++) {
                int p = earliestPe(readyByPe[j]);
                if (readyByPe[j][p] < readyByPe[bestVm][bestPe]) {
                    bestVm = j;
                    bestPe = p;
                }
            }
            double duration = c.getLength() / vms.get(bestVm).getMips();
            c.setVm(vms.get(bestVm));
            readyByPe[bestVm][bestPe] += duration;
            vmBusy[bestVm] += duration;
        }
    }

    /** MCT: task diassign ke PE dengan completion time terkecil. */
    static void mctAssign(List<Cloudlet> cloudlets, List<Vm> vms, double[][] readyByPe, double[] vmBusy) {
        for (Cloudlet c : cloudlets) {
            int bestVm = 0, bestPe = 0;
            double minCt = Double.MAX_VALUE;
            for (int j = 0; j < vms.size(); j++) {
                int p = earliestPe(readyByPe[j]);
                double ct = readyByPe[j][p] + c.getLength() / vms.get(j).getMips();
                if (ct < minCt) {
                    minCt = ct;
                    bestVm = j;
                    bestPe = p;
                }
            }
            c.setVm(vms.get(bestVm));
            readyByPe[bestVm][bestPe] = minCt;
            vmBusy[bestVm] += c.getLength() / vms.get(bestVm).getMips();
        }
    }

    /** Min-Min: pasangan (task, VM) dengan CT terkecil di antara task tersisa dijalankan duluan. */
    static void minMinAssign(List<Cloudlet> cloudlets, List<Vm> vms, double[][] readyByPe, double[] vmBusy) {
        int n = cloudlets.size();
        int m = vms.size();
        boolean[] done = new boolean[n];
        List<Cloudlet> assignmentOrder = new ArrayList<>(n);
        int[] pe = new int[m];

        for (int assigned = 0; assigned < n; assigned++) {
            for (int j = 0; j < m; j++) pe[j] = earliestPe(readyByPe[j]);

            int winC = -1, winV = -1;
            double globalMin = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                long len = cloudlets.get(i).getLength();
                for (int j = 0; j < m; j++) {
                    double ct = readyByPe[j][pe[j]] + len / vms.get(j).getMips();
                    if (ct < globalMin) {
                        globalMin = ct;
                        winC = i;
                        winV = j;
                    }
                }
            }
            Cloudlet winner = cloudlets.get(winC);
            winner.setVm(vms.get(winV));
            assignmentOrder.add(winner);
            readyByPe[winV][pe[winV]] = globalMin;
            vmBusy[winV] += winner.getLength() / vms.get(winV).getMips();
            done[winC] = true;
        }
        cloudlets.clear();
        cloudlets.addAll(assignmentOrder);
    }

    static List<Long> readCsv(String path, int limit) {
        List<Long> lengths = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // header
            String line;
            while (lengths.size() < limit && (line = br.readLine()) != null) {
                lengths.add(Long.parseLong(line.split(",")[1].trim()));
            }
        } catch (IOException | NumberFormatException e) {
            System.out.println("[ERROR] Gagal membaca " + path + ": " + e.getMessage());
        }
        return lengths;
    }

    static int planRep(String csvFile) {
        if (csvFile.contains("seed1")) return 1;
        if (csvFile.contains("seed2")) return 2;
        return 3;
    }
}
