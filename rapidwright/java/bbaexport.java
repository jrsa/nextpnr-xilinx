
package dev.fpga.rapidwright;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.util.Utils;
import jnr.ffi.annotations.In;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class bbaexport {

    public static String sitePinToGlobalWire(HashSet<Node> discoveredWires, Device d, Site s, String pinname) {
        String tw = s.getTileWireNameFromPinName(pinname);
        Wire w = d.getWire(s.getTile().getName() + "/" + tw);
        Node wn = w.getNode();
        discoveredWires.add(wn);
        return wn.getTile().getName() + "/" + wn.getWireName();
    }

    enum NextpnrPipType {
        TILE_ROUTING,
        SITE_ENTRANCE,
        SITE_EXIT,
        SITE_INTERNAL
    }

    static class NextpnrBelPin {
        public NextpnrBelPin(int bel, String port) {
            this.bel = bel;
            this.port = makeConstId(port);
        }
        public int bel;
        public int port;
    }

    static class NextpnrWire {

        public int name;
        public int index;

        public NextpnrWire(String name, int index, int intent) {
            this.name = makeConstId(name);
            this.index = index;
            this.is_site = false;
            this.intent = intent;
            this.pips_uh = new ArrayList<>();
            this.pips_dh = new ArrayList<>();
            this.belpins = new ArrayList<>();
        }

        public boolean is_site;
        public int site;
        public int intent;
        public ArrayList<Integer> pips_uh, pips_dh;
        public ArrayList<NextpnrBelPin> belpins;
    }

    static class NextpnrPip {

        public NextpnrPip(int index, int from, int to, int delay, NextpnrPipType type) {
            this.index = index;
            this.from = from;
            this.to = to;
            this.delay = delay;
            this.type = type;
        }

        public int index;
        public int from, to;
        public int delay;
        public NextpnrPipType type;

        public int bel = -1;
        public int site = -1;
        public int siteVariant = -1;
    }

    static class NextpnrBelWire {
        public int name;
        public int port_type;
        public int wire;
    }

    static class NextpnrBel {

        public NextpnrBel(String name, int index, String type, String nativeType, int site, int siteVariant, int z, int isRouting) {
            this.name = makeConstId(name);
            this.index = index;
            this.type = makeConstId(type);
            this.nativeType = makeConstId(type);
            this.site = site;
            this.siteVariant = siteVariant;
            this.z = z;
            this.isRouting = isRouting;
            this.belports = new ArrayList<>();
        }
        public int name;
        public int index;
        public int type, nativeType;
        public int site, siteVariant;
        public int isRouting;
        public int z;
        public ArrayList<NextpnrBelWire> belports;
    }

    private static ArrayList<String> constIds = new ArrayList<>();
    private static HashMap<String, Integer> knownConstIds = new HashMap<>();

    static class NextpnrTileType {
        public int index;
        public int type;
        public ArrayList<NextpnrBel> bels;
        public ArrayList<NextpnrWire> wires;
        public ArrayList<NextpnrPip> pips;

        public int tile_wire_count = 0; // excluding site wires
        public int row_gnd_wire_index, row_vcc_wire_index, global_gnd_wire_index, global_vcc_wire_index;
        public HashMap<String, Integer> siteWiresToWireIndex;

        private int siteWireToWire(Site s, String wire) {
            String key = s.getSiteTypeEnum().toString() + s.getSiteIndexInTile() + "/" + wire;
            if (siteWiresToWireIndex.containsKey(key))
                return siteWiresToWireIndex.get(key);
            NextpnrWire nw = new NextpnrWire(wire, wires.size(), makeConstId("SITE_WIRE"));
            nw.is_site = true;
            nw.site = s.getSiteIndexInTile();
            if (wire.equals("GND_WIRE"))
                nw.intent = makeConstId("INTENT_SITE_GND");
            else
                nw.intent = makeConstId("INTENT_SITE_WIRE");
            wires.add(nw);
            siteWiresToWireIndex.put(key, nw.index);
            return nw.index;
        }

        private NextpnrBel addBel(SiteInst s, int siteVariant, BEL b) {
            if (b.getBELClass() == BELClass.PORT)
                return null;
            NextpnrBel nb = new NextpnrBel(b.getName(),
                    bels.size(), getBelTypeOverride(b.getBELType()), b.getBELType(), s.getSite().getSiteIndexInTile(), siteVariant, getBelZoverride(s.getTile(), b),
                    (b.getBELClass() == BELClass.RBEL) ? 1 : 0);
            bels.add(nb);
            belsInTile.put(s.getTile(), belsInTile.getOrDefault(s.getTile(), 0) + 1);

            for (BELPin bp : b.getPins()) {
                NextpnrBelWire nport = new NextpnrBelWire();
                nport.port_type = bp.isBidir() ? 2 : (bp.isOutput() ? 1 : 0);
                nport.name = makeConstId(bp.getName());
                nport.wire = siteWireToWire(s.getSite(), bp.getSiteWireName());
                nb.belports.add(nport);

                wires.get(nport.wire).belpins.add(new NextpnrBelPin(nb.index, bp.getName()));

                // For HARD0 bels, create a pip from the tile-wide ground pseudo-wire to the HARDGND output
                // for ease of constant routing in nextpnr
                if (b.getBELType().equals("HARD0") && bp.getName().equals("0")) {
                    NextpnrPip np = new NextpnrPip(pips.size(), row_gnd_wire_index, siteWireToWire(s.getSite(), bp.getSiteWireName()),
                            0, NextpnrPipType.SITE_ENTRANCE);
                    wires.get(np.from).pips_dh.add(np.index);
                    wires.get(np.to).pips_uh.add(np.index);
                    np.bel = makeConstId(b.getName());
                    np.site = s.getSite().getSiteIndexInTile();
                    np.siteVariant = siteVariant;
                    pips.add(np);
                }
            }

            return nb;
        }

        private NextpnrPip addSitePIP(SiteInst s, int siteVariant, SitePIP sp) {
            if (sp.getBEL().getBELType().contains("LUT"))
                return null; // Ignore LUT route-through pips for now
            NextpnrPip np = new NextpnrPip(pips.size(), siteWireToWire(s.getSite(), sp.getInputPin().getSiteWireName()),
                    siteWireToWire(s.getSite(), sp.getOutputPin().getSiteWireName()), 0, NextpnrPipType.SITE_INTERNAL);
            wires.get(np.from).pips_dh.add(np.index);
            wires.get(np.to).pips_uh.add(np.index);

            np.bel = makeConstId(sp.getBELName());
            np.site = s.getSite().getSiteIndexInTile();
            np.siteVariant = siteVariant;

            pips.add(np);
            return np;
        }

        private NextpnrPip addSiteIOPIP(Device d, Site s, BELPin bp) {
            NextpnrPip np;
            String sitePinName = bp.getConnectedSitePinName();
            if (bp.isOutput() || bp.isBidir())
                np = new NextpnrPip(pips.size(), siteWireToWire(s, bp.getSiteWireName()), s.getTile().getWireIndex(s.getTileWireNameFromPinName(sitePinName)),
                        0, NextpnrPipType.SITE_EXIT);
            else {
                if (((s.getSiteTypeEnum() == SiteTypeEnum.SLICEL || s.getSiteTypeEnum() == SiteTypeEnum.SLICEM))) {
                    // Permutation pseudo-pips for LUT pins
/*
                    String pn = bp.getSiteWireName();
                    if (pn.length() == 2 && "ABCDEFGH".contains(pn.substring(0, 1)) && "12345".contains(pn.substring(1, 2))) {
                        // No permutation for 6 ATM
                        int i = "12345".indexOf(pn.substring(1, 2)) + 1;
                        for (int j = 1; j <= 5; j++) {
                            if (i == j)
                                continue;
                            NextpnrPip pp = new NextpnrPip(pips.size(), s.getTile().getWireIndex(s.getTileWireNameFromPinName(pn.substring(0, 1) + j)), siteWireToWire(s, bp.getSiteWireName()),
                                    0, NextpnrPipType.SITE_ENTRANCE);
                            wires.get(pp.from).pips_dh.add(pp.index);
                            wires.get(pp.to).pips_uh.add(pp.index);
                            pips.add(pp);
                        }

                    }

 */
                }
                np = new NextpnrPip(pips.size(), s.getTile().getWireIndex(s.getTileWireNameFromPinName(sitePinName)), siteWireToWire(s, bp.getSiteWireName()),
                        0, NextpnrPipType.SITE_ENTRANCE);

            }
            wires.get(np.from).pips_dh.add(np.index);
            wires.get(np.to).pips_uh.add(np.index);
            pips.add(np);
            return np;
        }

        private NextpnrPip addPIP(PIP p, boolean reverse) {
            // YUCK! Waiting for proper interconnect delays in RapidWright ;)
            int delay = p.getStartWire().getNode().getTile().getManhattanDistance(p.getEndWire().getNode().getTile());

            NextpnrPip np = new NextpnrPip(pips.size(), reverse ?  p.getEndWireIndex() : p.getStartWireIndex(), reverse ?  p.getStartWireIndex() : p.getEndWireIndex(), delay, NextpnrPipType.TILE_ROUTING);

            wires.get(np.from).pips_dh.add(np.index);
            wires.get(np.to).pips_uh.add(np.index);
            pips.add(np);
            return np;
        }

        private NextpnrPip addPseudoPIP(int from, int to) {
            NextpnrPip np = new NextpnrPip(pips.size(), from, to, 0, NextpnrPipType.TILE_ROUTING);
            wires.get(np.from).pips_dh.add(np.index);
            wires.get(np.to).pips_uh.add(np.index);
            pips.add(np);
            return np;
        }

        private void addPsuedoBel(Tile t, String name, String type, String pinName, int wire) {
            NextpnrBel nb = new NextpnrBel(name, bels.size(), type, type, -1, 0, bels.size(), 0);
            NextpnrBelWire port = new NextpnrBelWire();
            port.name = makeConstId(pinName);
            port.port_type = 1;
            port.wire = wire;
            nb.belports.add(port);
            wires.get(wire).belpins.add(new NextpnrBelPin(nb.index, pinName));
            bels.add(nb);
        }

        public void importTile(Device d, Design des, Tile t) {

            type = makeConstId(t.getTileTypeEnum().name());
            bels = new ArrayList<>();
            wires = new ArrayList<>();
            pips = new ArrayList<>();
            siteWiresToWireIndex = new HashMap<>();

            for (String wn : t.getWireNames()) {
                int index = wires.size();
                Wire w = new Wire(t, wn);
                wires.add(new NextpnrWire(wn, index, makeConstId(w.getIntentCode().toString())));
            }

            // Add a special wires
            row_gnd_wire_index = wires.size();
            wires.add(new NextpnrWire("PSEUDO_GND_WIRE_ROW", row_gnd_wire_index, makeConstId("PSEUDO_GND")));
            row_vcc_wire_index = wires.size();
            wires.add(new NextpnrWire("PSEUDO_VCC_WIRE_ROW", row_vcc_wire_index, makeConstId("PSEUDO_VCC")));
            global_gnd_wire_index = wires.size();
            wires.add(new NextpnrWire("PSEUDO_GND_WIRE_GLBL", global_gnd_wire_index, makeConstId("PSEUDO_GND")));
            global_vcc_wire_index = wires.size();
            wires.add(new NextpnrWire("PSEUDO_VCC_WIRE_GLBL", global_vcc_wire_index, makeConstId("PSEUDO_VCC")));

            tile_wire_count = wires.size();
            int autoidx = 0;
            for (Site s : t.getSites()) {
                HashSet<BELPin> sitePins = new HashSet<>();
                ArrayList<SiteTypeEnum> variants = new ArrayList<>();
                variants.add(s.getSiteTypeEnum());
                variants.addAll(Arrays.asList(s.getAlternateSiteTypeEnums()));

                for (int variant = 0; variant < variants.size(); variant++) {

                    SiteInst si = new SiteInst(t.getName() + "_" + (autoidx++), des, variants.get(variant), s);

                    HashSet<SitePIP> sitePips = new HashSet<>();
                    for (BEL b : si.getBELs()) {
                        addBel(si, variant, b);
                        for (BELPin bp : b.getPins()) {
                            String sitePin = bp.getConnectedSitePinName();
                            if (sitePin != null)
                                sitePins.add(bp);
                            sitePips.addAll(bp.getSitePIPs());
                        }

                    }
                    for (SitePIP sp : sitePips)
                        addSitePIP(si, variant, sp);

                    si.unPlace();
                }


                for (BELPin bp : sitePins)
                    addSiteIOPIP(d, s, bp);
            }
            TileTypeEnum tt = t.getTileTypeEnum();
            boolean isLogic = (tt == TileTypeEnum.CLEM || tt == TileTypeEnum.CLEM_R || tt == TileTypeEnum.CLEL_L || tt == TileTypeEnum.CLEL_R);
            for (PIP p : t.getPIPs()) {
                // FIXME: route-thru PIPs (site pips will capture some of these anyway)
                if (p.isRouteThru() && isLogic)
                    continue;
                addPIP(p, false);
                if (p.isBidirectional())
                    addPIP(p, true);
            }
            // Add pseudo-bels driving Vcc and GND
            addPsuedoBel(t, "PSEUDO_GND_BEL", "PSEUDO_GND", "Y", global_gnd_wire_index);
            addPsuedoBel(t, "PSEUDO_VCC_BEL", "PSEUDO_VCC", "Y", global_vcc_wire_index);
            // Add pseudo global->row Vcc and GND pips
            addPseudoPIP(global_gnd_wire_index, row_gnd_wire_index);
            addPseudoPIP(global_vcc_wire_index, row_vcc_wire_index);
        }
    }

    static class NextpnrSiteInst {
        public String name;
        public int site_x, site_y;
        public int inter_x, inter_y;
    }
    static class NextpnrTileInst {
        public int index;
        public String name;
        public int type;

        public Integer[] tilewire_to_node;

        public ArrayList<NextpnrSiteInst> sites;
    }

    private static String getBelTypeOverride(String type) {
        if (type.endsWith("6LUT"))
            return "SLICE_LUTX";
        if (type.endsWith("5LUT"))
            return "SLICE_LUTX";
        if (type.length() == 4 && type.endsWith("FF2"))
            return "SLICE_FFX";
        if (type.length() == 3 && type.endsWith("FF"))
            return "SLICE_FFX";
        String[] iobParts = {"_PAD_", "_PULL_", "_IBUFCTRL_", "_INBUF_", "_OUTBUF_"};
        for (String p : iobParts) {
            if (type.contains(p))
                return "IOB_" + p.replace("_", "");
        }

        String[] iolParts = {"COMBUF_", "IDDR_", "IPFF_", "OPFF_", "OPTFF_", "TFF_"};
        for (String p : iolParts) {
            if (type.startsWith(p))
                return "IOL_" + p.replace("_", "");
        }

        if (type.endsWith("_VREF"))
            return "IOB_VREF";

        if (type.startsWith("PSS_ALTO_CORE_PAD_"))
            return "PSS_PAD";

        if (type.startsWith("LAGUNA_RX_REG") || type.startsWith("LAGUNA_TX_REG"))
            return "LAGUNA_REGX";

        if (type.startsWith("BSCAN"))
            return "BSCAN";

        if (type.equals("BUFGCTRL_BUFGCTRL"))
            return "BUFGCTRL";

        return type;
    }


    private static HashMap<Tile, Integer> belsInTile = new HashMap<>();

    private static int makeConstId(String s) {
        if (knownConstIds.containsKey(s))
            return knownConstIds.get(s);
        knownConstIds.put(s, constIds.size());
        constIds.add(s);
        return constIds.size() - 1;
    }

    private static int getBelZoverride(Tile t, BEL b) {
        if (b.getSiteTypeEnum() != SiteTypeEnum.SLICEL && b.getSiteTypeEnum() != SiteTypeEnum.SLICEM)
            return belsInTile.getOrDefault(t, 0);

        // LUTs, FFs, and default muxes follow a regular pattern
        // z[6..4] = z-index (A-H)
        // z[3..0] = type
        // Types: [6LUT, 5LUT, FF, FF2, FFMUX1, FFMUX2, OUTMUX, F7MUX, F8MUX, F9MUX, CARRY8, CLKINV, RSTINV, HARD0]
        String subslices = "ABCDEFGH";
        String name = b.getName();
        String[] postfixes = {"6LUT", "5LUT", "FF", "FF2"};
        for (int i = 0; i < postfixes.length; i++) {
            if (name.length() == postfixes[i].length() + 1 && name.substring(1).equals(postfixes[i])) {
                return subslices.indexOf(name.charAt(0)) << 4 | i;
            }
        }
        if (name.startsWith("FFMUX"))
            return subslices.indexOf(name.charAt(5)) << 4 | (name.charAt(6) == '2' ? 5 : 4);
        if (name.startsWith("OUTMUX"))
            return subslices.indexOf(name.charAt(6)) << 4 | 6;

        // More special features
        switch (name) {
            case "F7MUX_AB":
                return 0x07;
            case "F7MUX_CD":
                return 0x27;
            case "F7MUX_EF":
                return 0x47;
            case "F7MUX_GH":
                return 0x67;
            case "F8MUX_BOT":
                return 0x08;
            case "F8MUX_TOP":
                return 0x48;
            case "F9MUX":
                return 0x09;
            case "CARRY8":
                return 0x0A;
            case "CLK1INV":
                return 0x0B;
            case "CLK2INV":
                return 0x4B;
            case "LCLKINV":
                return 0x1B;
            case "RST_ABCDINV":
                return 0x0C;
            case "RST_EFGHINV":
                return 0x4C;
            case "HARD0":
                return 0x0D;
        }
        assert(false);
        return -1;
    }

    public static ArrayList<NextpnrTileType> tileTypes = new ArrayList<>();
    public static HashMap<TileTypeEnum, Integer> tileTypeIndices = new HashMap<>();

    public static ArrayList<NextpnrTileInst> tileInsts = new ArrayList<>();
    public static HashMap<Integer, NextpnrTileInst> tileToTileInst = new HashMap<>();


    public static void main(String[] args) throws IOException {

        if (args.length < 3) {
            System.err.println("Usage: bbaexport <device> <constids.inc> <output.bba>");
            System.err.println("   e.g bbaexport xczu2cg-sbva484-1-e ./rapidwright/constids.inc ./rapidwright/xczu2cg.bba");
            System.err.println("   Use bbasm to convert bba to bin for nextpnr");
            System.exit(1);
        }

        // Device d = Device.getDevice("xczu2cg-sbva484-1-e");

        // Seems like we need to use a Design to create SiteInsts to probe alternate site types...
        Design des = new Design("top",  args[0]);
        //Design des = new Design("top", "xczu2cg-sbva484-1-e");
        Device d = des.getDevice();
        // Known constids
        Scanner scanner = new Scanner(new File(args[1]));
        int known_id_count = 0;
        makeConstId("");
        ++known_id_count;
        while (scanner.hasNextLine()) {
            String nl = scanner.nextLine().trim();
            if (nl.length() < 3 || !nl.substring(0, 2).equals("X("))
                continue;
            makeConstId(nl.substring(2, nl.length() - 1));
            ++known_id_count;
        }

        // Unique tiletypes
        HashSet<TileTypeEnum> seenTileTypes = new HashSet<>();
        for (Tile t : d.getAllTiles()) {
            if (seenTileTypes.contains(t.getTileTypeEnum()))
                continue;
            seenTileTypes.add(t.getTileTypeEnum());
            tileTypeIndices.put(t.getTileTypeEnum(), tileTypes.size());

            NextpnrTileType ntt = new NextpnrTileType();
            ntt.index = tileTypes.size();
            ntt.importTile(d, des, t);
            tileTypes.add(ntt);
            System.out.println("Processed tile type " + t.getTileTypeEnum().name());
        }

        // Tile entries
        for (int y = 0; y < d.getRows(); y++) {
            for (int x = 0; x < d.getColumns(); x++) {
                Tile t = d.getTile(y, x);
                NextpnrTileInst nti = new NextpnrTileInst();
                nti.name = t.getName();
                nti.type = tileTypeIndices.get(t.getTileTypeEnum());
                nti.index = tileInsts.size();
                nti.sites = new ArrayList<>();
                for (Site s : t.getSites()) {
                    NextpnrSiteInst nsi = new NextpnrSiteInst();
                    nsi.name = s.getName();
                    nsi.site_x = s.getInstanceX();
                    nsi.site_y = s.getInstanceY();

                    Tile intert = null;
                    try {
                        intert = s.getIntTile();
                    } catch(java.lang.ArrayIndexOutOfBoundsException e) {

                    }
                    if (intert != null) {
                        nsi.inter_x = intert.getColumn();
                        nsi.inter_y = intert.getRow();
                    } else {
                        nsi.inter_x = -1;
                        nsi.inter_y = -1;
                    }
                    nti.sites.add(nsi);
                }
                nti.tilewire_to_node = new Integer[t.getWireCount() + 4]; // +1 accounts for vcc/ground pseudo-wires
                Arrays.fill(nti.tilewire_to_node, -1);
                tileInsts.add(nti);
                tileToTileInst.put(t.getRow() * d.getColumns() + t.getColumn(), nti);
            }
        }

        FileWriter bbaf = new FileWriter(args[2], false);
        PrintWriter bba = new PrintWriter(bbaf);


        // Header
        bba.println("pre #include \"nextpnr.h\"");
        bba.println("pre NEXTPNR_NAMESPACE_BEGIN");
        bba.println("post NEXTPNR_NAMESPACE_END");
        bba.println("push chipdb_blob");
        bba.println("offset32");
        bba.println("ref chip_info chip_info");

        bba.println("label extra_constid_strs");
        for (int i = known_id_count; i < constIds.size(); i++)
            bba.printf("str |%s|\n", constIds.get(i));
        bba.println("align");
        // Constant IDs additional to constids.inc
        bba.println("label extra_constids");
        bba.printf("u32 %d\n", known_id_count);
        bba.printf("u32 %d\n", constIds.size() - known_id_count);
        bba.println("ref extra_constid_strs");

        // Tiletypes
        for (NextpnrTileType tt : tileTypes) {
            // List of wires on bels in tile
            for (NextpnrBel b : tt.bels) {
                bba.printf("label t%db%d_wires\n", tt.index, b.index);
                for (NextpnrBelWire bw : b.belports) {
                    bba.printf("u32 %d\n", bw.name); // port name
                    bba.printf("u32 %d\n", bw.port_type); // port type
                    bba.printf("u32 %d\n", bw.wire); // index of connected tile wire
                }
            }
            // List of uphill pips, downhill pips and bel ports on wires in tile
            for (NextpnrWire w : tt.wires) {
                bba.printf("label t%dw%d_uh\n", tt.index, w.index);
                for (int uh : w.pips_uh) {
                    bba.printf("u32 %d\n", uh); // index of uphill pip
                }
                bba.printf("label t%dw%d_dh\n", tt.index, w.index);
                for (int dh : w.pips_dh) {
                    bba.printf("u32 %d\n", dh); // index of downhill pip
                }
                bba.printf("label t%dw%d_bels\n", tt.index, w.index);
                for (NextpnrBelPin bp : w.belpins) {
                    bba.printf("u32 %d\n", bp.bel); // index of bel in tile
                    bba.printf("u32 %d\n", bp.port); // bel port constid
                }
            }
            // Bel data for tiletype
            bba.printf("label t%d_bels\n", tt.index);
            for (NextpnrBel b : tt.bels) {
                bba.printf("u32 %d\n", b.name); //name constid
                bba.printf("u32 %d\n", b.type); //type (compatible type for nextpnr) constid
                bba.printf("u32 %d\n", b.nativeType); //native type (original type in RapidWright) constid
                bba.printf("u32 %d\n", b.belports.size()); //number of bel port wires
                bba.printf("ref t%db%d_wires\n", tt.index, b.index); //ref to list of bel wires
                bba.printf("u16 %d\n", b.z); // bel z position
                bba.printf("u16 %d\n", b.site); // bel site index in tile
                bba.printf("u16 %d\n", b.siteVariant); // bel site variant
                bba.printf("u16 %d\n", b.isRouting);
            }

            // Wire data for tiletype
            bba.printf("label t%d_wires\n", tt.index);
            for (NextpnrWire w : tt.wires) {
                bba.printf("u32 %d\n", w.name); //name constid
                bba.printf("u32 %d\n", w.pips_uh.size()); //number of uphill pips
                bba.printf("u32 %d\n", w.pips_dh.size()); //number of downhill pips
                bba.printf("ref t%dw%d_uh\n", tt.index, w.index); //ref to list of uphill pips
                bba.printf("ref t%dw%d_dh\n", tt.index, w.index); //ref to list of downhill pips
                bba.printf("u32 %d\n",  w.belpins.size()); // number of bel pins
                bba.printf("ref t%dw%d_bels\n", tt.index, w.index); //ref to list of bel pins

                bba.printf("u16 %d\n", w.is_site ? w.site : -1); //site index or -1 if not a site wire
                bba.printf("u16 0\n"); //padding
                bba.printf("u32 %d\n", w.intent); //wire intent constid
            }

            // Pip data for tiletype
            bba.printf("label t%d_pips\n", tt.index);
            for (NextpnrPip p : tt.pips) {
                bba.printf("u32 %d\n", p.from); //src tile wire index
                bba.printf("u32 %d\n", p.to); //dst tile wire index
                bba.printf("u16 %d\n", p.delay); //"delay" (actually distance)
                bba.printf("u16 %d\n", p.type.ordinal()); // pip type/flags

                bba.printf("u32 %d\n", p.bel); //bel name constid for site pips
                bba.printf("u16 %d\n", p.site); //site index in tile for site pips
                bba.printf("u16 %d\n", p.siteVariant); //site variant index for site pips
            }

        }
        bba.printf("label tiletype_data\n");
        for (NextpnrTileType tt : tileTypes) {
            bba.printf("u32 %d\n", tt.type); //tile type name constid
            bba.printf("u32 %d\n", tt.bels.size()); //number of bels
            bba.printf("ref t%d_bels\n", tt.index); //ref to list of bels
            bba.printf("u32 %d\n", tt.wires.size()); //number of wires
            bba.printf("ref t%d_wires\n", tt.index); //ref to list of wires
            bba.printf("u32 %d\n", tt.pips.size()); //number of pips
            bba.printf("ref t%d_pips\n", tt.index); //ref to list of pips
        }

        // Nodes
        HashSet<TileTypeEnum> intTileTypes = Utils.getIntTileTypes();
        HashSet<Long> seenNodes = new HashSet<>();
        int curr = 0, total = d.getAllTiles().size();
        ArrayList<Integer> nodeWireCount = new ArrayList<>(), nodeIntent = new ArrayList<>();

        for (int row = 0; row < d.getRows(); row++) {
            HashSet<Node> gndNodes = new HashSet<>(), vccNodes = new HashSet<>();
            for (int col = 0; col < d.getColumns(); col++) {
                Tile t = d.getTile(row, col);
                ++curr;
                System.out.println("Processing nodes in tile " + curr + "/" + total);
                for (PIP p : t.getPIPs()) {
                    Node[] nodes = {p.getStartNode(), p.getEndNode()};
                    // FIXME: best way to discover nodes in tile?
                    for (Node n : nodes) {
                        long flatIndex = (long)(n.getTile().getRow() * d.getColumns() + n.getTile().getColumn()) << 32 | n.getWire();
                        if (seenNodes.contains(flatIndex))
                            continue;
                        seenNodes.add(flatIndex);

                        String wn = n.getWireName();
                        if (wn.startsWith("GND_WIRE")) {
                            gndNodes.add(n);
                            continue;
                        }
                        if (wn.startsWith("VCC_WIRE")) {
                            vccNodes.add(n);
                            continue;
                        }
                        if (n.getAllWiresInNode().length > 1) {
                            //nn.tilewires = new NextpnrTileWireRef[n.getAllWiresInNode().length];
                            bba.printf("label n%d_tw\n", nodeWireCount.size());
                            // Add interconnect tiles first for better delay estimates in nextpnr
                            for (int j = 0; j < 2; j++) {
                                for (Wire w : n.getAllWiresInNode()) {
                                    if (intTileTypes.contains(w.getTile().getTileTypeEnum()) != (j == 0))
                                        continue;
                                    int tileIndex = w.getTile().getRow() * d.getColumns() + w.getTile().getColumn();
                                    //nn.tilewires[i] = new NextpnrTileWireRef(tileToTileInst.get(tileIndex).index, w.getWireIndex());

                                    bba.printf("u32 %d\n", tileToTileInst.get(tileIndex).index); //tile inst index
                                    bba.printf("u32 %d\n", w.getWireIndex());

                                    tileToTileInst.get(tileIndex).tilewire_to_node[w.getWireIndex()] = nodeWireCount.size();

                                }
                            }
                            Wire nw = new Wire(n.getTile(), n.getWire());
                            nodeIntent.add(makeConstId(nw.getIntentCode().toString()));
                            nodeWireCount.add(n.getAllWiresInNode().length);
                        }
                    }
                }
            }
            // Connect up row and column ground nodes
            for (int i = 0; i < 2; i++) {
                bba.printf("label n%d_tw\n", nodeWireCount.size());
                int wireCount = 0;
                for (Node n : (i == 1) ? vccNodes : gndNodes) {
                    for (Wire w : n.getAllWiresInNode()) {
                        int tileIndex = w.getTile().getRow() * d.getColumns() + w.getTile().getColumn();
                        bba.printf("u32 %d\n", tileToTileInst.get(tileIndex).index);
                        bba.printf("u32 %d\n", w.getWireIndex());
                        tileToTileInst.get(tileIndex).tilewire_to_node[w.getWireIndex()] = nodeWireCount.size();
                        wireCount++;
                    }
                }

                for (int col = 0; col < d.getColumns(); col++) {
                    Tile t = d.getTile(row, col);
                    int tileIndex = t.getRow() * d.getColumns() + t.getColumn();
                    bba.printf("u32 %d\n", tileToTileInst.get(tileIndex).index);
                    int wireIndex = (i == 1) ? tileTypes.get(tileToTileInst.get(tileIndex).type).row_vcc_wire_index : tileTypes.get(tileToTileInst.get(tileIndex).type).row_gnd_wire_index;
                    bba.printf("u32 %d\n", wireIndex);
                    tileToTileInst.get(tileIndex).tilewire_to_node[wireIndex] = nodeWireCount.size();
                    wireCount++;
                }

                nodeWireCount.add(wireCount);
                nodeIntent.add(makeConstId(i == 1 ? "PSEUDO_VCC" : "PSEUDO_GND"));
            }
        }
        // Create the global Vcc and Ground nodes
        for (int i = 0; i < 2; i++) {
            bba.printf("label n%d_tw\n", nodeWireCount.size());
            int wireCount = 0;
            for (int row = 0; row < d.getRows(); row++) {
                Tile t = d.getTile(row, 0);
                int tileIndex = t.getRow() * d.getColumns() + t.getColumn();
                bba.printf("u32 %d\n", tileToTileInst.get(tileIndex).index);
                int wireIndex = (i == 1) ? tileTypes.get(tileToTileInst.get(tileIndex).type).global_vcc_wire_index : tileTypes.get(tileToTileInst.get(tileIndex).type).global_gnd_wire_index;
                bba.printf("u32 %d\n", wireIndex);
                tileToTileInst.get(tileIndex).tilewire_to_node[wireIndex] = nodeWireCount.size();
                wireCount++;
            }

            nodeWireCount.add(wireCount);
            nodeIntent.add(makeConstId(i == 1 ? "PSEUDO_VCC" : "PSEUDO_GND"));
        }

        for (NextpnrTileInst ti : tileInsts) {
            // Tilewire -> node mappings
            bba.printf("label ti%d_wire_to_node\n", ti.index);
            for (int w2n : ti.tilewire_to_node)
                bba.printf("u32 %d\n", w2n);
            bba.printf("label ti%d_sites\n", ti.index);
            for (NextpnrSiteInst si : ti.sites) {
                bba.printf("str |%s|\n", si.name);
                bba.printf("u32 %d\n", si.site_x); //X nominal coordinate
                bba.printf("u32 %d\n", si.site_y); //Y nominal coordinate
                bba.printf("u32 %d\n", si.inter_x); //X intercon tile coordinate
                bba.printf("u32 %d\n", si.inter_y); //Y intercon coordinate
            }
        }
        bba.printf("label tile_insts\n");
        for (NextpnrTileInst ti : tileInsts) {
            bba.printf("str |%s|\n", ti.name); //tile name
            bba.printf("u32 %d\n", ti.type); //tile type index into tiletype_data
            bba.printf("u32 %d\n", ti.tilewire_to_node.length); //length of tilewire_to_node
            bba.printf("ref ti%d_wire_to_node\n", ti.index); //ref to tilewire_to_node
            bba.printf("u32 %d\n", ti.sites.size());
            bba.printf("ref ti%d_sites\n", ti.index); //ref to list of site names
        }

        bba.printf("label nodes\n");
        for (int i = 0; i < nodeWireCount.size(); i++) {
            bba.printf("u32 %d\n", nodeWireCount.get(i)); //number of tilewires in node
            bba.printf("u32 %d\n", nodeIntent.get(i)); //node intent constid
            bba.printf("ref n%d_tw\n", i); //ref to list of tilewires
        }
        // Chip info
        bba.println("label chip_info");
        bba.printf("str |%s|\n", d.getDeviceName()); //device name
        bba.printf("str |RapidWright|\n"); //generator
        bba.printf("u32 %d\n", 1); //version
        bba.printf("u32 %d\n", d.getColumns()); //width
        bba.printf("u32 %d\n", d.getRows()); //height
        bba.printf("u32 %d\n", tileInsts.size()); //number of tiles
        bba.printf("u32 %d\n", tileTypes.size()); //number of tiletypes
        bba.printf("u32 %d\n", nodeWireCount.size()); //number of nodes
        bba.println("ref tiletype_data"); // reference to tiletype data
        bba.println("ref tile_insts"); // reference to tile instances
        bba.println("ref nodes"); // reference to node data
        bba.println("ref extra_constids"); // reference to bel data
        bba.println("pop");
        bbaf.close();
    }
}