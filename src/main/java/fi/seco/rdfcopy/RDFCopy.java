package fi.seco.rdfcopy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fortytwo.sesametools.nquads.NQuadsFormat;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.jena.riot.RIOT;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFWriterRegistry;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriterFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import fi.seco.rdfio.RDFReader;
import fi.seco.rdfio.RDFReader.IRDFHandler;
import fi.seco.rdfio.RDFWriter;
import fi.seco.rdfio.RDFWriter.IRDFWriter;
import fi.seco.rdfobject.BNodeResourceRDFObject;
import fi.seco.rdfobject.IQuad;
import fi.seco.rdfobject.IRDFObject;
import fi.seco.rdfobject.Quad;

public class RDFCopy {

	private static class Args {

		@Parameter(description = "Files/directories to load (recursively). Last one is the output file. If only one file is given, print a count of the triples in that file", required = true)
		List<String> files;

		@Parameter(names = "--blacklist", description = "Blacklist of files/directories to not load")
		List<String> blacklist;

		@Parameter(names = "--split", description = "Split input into files of n triples")
		Long splitTriples;
		
		@Parameter(names = "--input-format", description = "Explicit RDF format for input")
		String inputFormat;
		
		@Parameter(names = "--output-format", description = "Explicit RDF format for output")
		String outputFormat;

		@Parameter(names = "--nopretty", description = "Don't write a pretty RDF file even if possible (Turtle, N3, RDF/XML, TriG, TriX). Writing will be faster.")
		boolean nopretty = false;

	}

	private static void recursivelyAddAsUrls(List<String> urls, File f, List<String> blacklist) {
		if (blacklist != null) for (String s : blacklist)
			if (f.getAbsolutePath().startsWith(s)) return;
		if (f.isDirectory()) {
			if (!f.getName().startsWith(".")) for (File f2 : f.listFiles())
				recursivelyAddAsUrls(urls, f2, blacklist);
		} else {
			String url = "file://" + f.getAbsolutePath();
			if (RDFReader.canRead(url)) urls.add(url);
		}
	}

	private static IRDFWriter initWriter(String outputFile, String baseIRI, Map<String, String> prefixes, boolean pretty) {
		IRDFWriter w = RDFWriter.getWriter(outputFile, pretty);
		if (w == null) {
			System.err.println("Can't write format of " + outputFile);
			System.exit(1);
		}
		if (baseIRI != null) w.setBaseIRI(baseIRI);
		for (Map.Entry<String, String> pe : prefixes.entrySet())
			w.setNameSpace(pe.getKey(), pe.getValue());
		w.endProlog();
		return w;
	}

	public static void main(String[] args) throws IOException {
		final Args argsP = new Args();
		JCommander jc = new JCommander(argsP);
		try {
			jc.parse(args);
		} catch (ParameterException e) {
			jc.usage();
			System.exit(1);
		}
		RIOT.init();
		RDFFormat.register(NQuadsFormat.NQUADS);
		RDFWriterRegistry.getInstance().add(new RDFXMLPrettyWriterFactory());
		final long count[] = new long[1];
		List<String> urls = new ArrayList<String>();
		final String outputFile = argsP.files.get(argsP.files.size() - 1);
		for (int i = 0; i < argsP.files.size() - 1; i++)
			recursivelyAddAsUrls(urls, new File(argsP.files.get(i)), argsP.blacklist);
		if (urls.isEmpty()) {
			long stime = System.currentTimeMillis();
			System.out.print("Reading " + outputFile);
			String url = "file://" + new File(outputFile).getAbsolutePath();
			if (!RDFReader.canRead(url)) {
				System.err.println("Couldn't read triples from " + outputFile);
				System.exit(1);
			}
			try {
				RDFReader.read("file://" + new File(outputFile).getAbsolutePath(), new IRDFHandler() {

					@Override
					public void visit(IQuad q) {
						count[0]++;
					}

					@Override
					public void setNameSpace(String prefix, String ns) {}

					@Override
					public void setBaseIRI(String baseIRI) {}

					@Override
					public void comment(String comment) {}
				});
			} catch (RDFParseException e) {
				long ctime = System.currentTimeMillis();
				System.err.println(String.format("Couldn't read triples from %s (managed to read %,d triples in %s, avg %,d statements/s)", outputFile, count[0], DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
				e.printStackTrace();
				System.exit(1);
			} catch (RDFHandlerException e) {
				long ctime = System.currentTimeMillis();
				System.err.println(String.format("Couldn't read triples from %s (managed to read %,d triples in %s, avg %,d statements/s)", outputFile, count[0], DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
				e.printStackTrace();
				System.exit(1);
			}
			long ctime = System.currentTimeMillis();
			System.out.println(String.format(". Read %,d triples in %s, avg %,d statements/s", count[0], DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
			System.exit(0);
		}

		final Map<String, String> prefixes = new HashMap<String, String>();
		final String[] baseIRI = new String[1];
		final int[] bicount = new int[] { 2 };
		for (String url : urls) {
			System.out.println("Reading prefixes from " + url);
			try {
				RDFReader.read(url, new IRDFHandler() {

					@Override
					public void visit(IQuad q) {
						throw new UnsupportedOperationException();
					}

					@Override
					public void setNameSpace(String prefix, String ns) {
						String cprefix = prefix;
						int count = 2;
						while (prefixes.containsKey(cprefix) && !ns.equals(prefixes.get(cprefix)))
							cprefix = prefix + count;
						prefixes.put(cprefix, ns);
					}

					@Override
					public void setBaseIRI(String baseIRIa) {
						if (baseIRI[0] == null)
							baseIRI[0] = baseIRIa;
						else if (baseIRIa != null && !baseIRI[0].equals(baseIRIa))
							prefixes.put("basens" + bicount[0]++, baseIRIa);
					}

					@Override
					public void comment(String comment) {
						throw new UnsupportedOperationException();
					}

				});
			} catch (UnsupportedOperationException e) {} catch (RDFParseException e) {
				System.err.println(String.format("Couldn't read prefixes from %s", url));
				e.printStackTrace();
			} catch (RDFHandlerException e) {
				System.err.println(String.format("Couldn't read prefixes from %s", url));
				e.printStackTrace();
			}
		}
		final long splitAt = argsP.splitTriples != null ? argsP.splitTriples : -1;
		final IRDFWriter[] w = new IRDFWriter[1];
		final int[] cursplit = new int[] { 0 };
		if (splitAt == -1)
			w[0] = initWriter(outputFile, baseIRI[0], prefixes, !argsP.nopretty);
		else w[0] = initWriter(outputFile.substring(0, outputFile.indexOf('.')) + "-" + (++cursplit[0]) + outputFile.substring(outputFile.indexOf('.')), baseIRI[0], prefixes, !argsP.nopretty);
		final long[] countSinceLastSplit = new long[1];
		long tcount = 0;
		long tstime = System.currentTimeMillis();
		long bnodeSi = 1;
		for (String url : urls) {
			final long bnodeS = bnodeSi++;
			count[0] = 0;
			System.out.println("Reading " + url);
			long stime = System.currentTimeMillis();
			try {
				RDFReader.read(url, new IRDFHandler() {

					@Override
					public void visit(IQuad q) {
						IRDFObject s = q.getSubject();
						IRDFObject p = q.getProperty();
						IRDFObject o = q.getObject();
						IRDFObject g = q.getGraph();
						count[0]++;
						if (splitAt != -1 && countSinceLastSplit[0]++ == splitAt) {
							long stime = System.currentTimeMillis();
							w[0].close();
							long ctime = System.currentTimeMillis();
							System.out.println(String.format("Closed (and possibly wrote) %s in %s (avg %,d statements/s if writing took place)", outputFile.substring(0, outputFile.indexOf('.')) + "-" + (cursplit[0]) + outputFile.substring(outputFile.indexOf('.')), DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), countSinceLastSplit[0] * 1000 / (ctime - stime + 1)));
							w[0] = initWriter(outputFile.substring(0, outputFile.indexOf('.')) + "-" + (++cursplit[0]) + outputFile.substring(outputFile.indexOf('.')), baseIRI[0], prefixes, !argsP.nopretty);
							countSinceLastSplit[0] = 1;
						}
						if (s.isBlankNode() || p.isBlankNode() || o.isBlankNode() || g.isBlankNode()) {
							if (s.isBlankNode())
								s = new BNodeResourceRDFObject("_:b" + bnodeS + "_" + s.getURI().replace(':', '_'));
							if (p.isBlankNode())
								p = new BNodeResourceRDFObject("_:b" + bnodeS + "_" + p.getURI().replace(':', '_'));
							if (o.isBlankNode())
								o = new BNodeResourceRDFObject("_:b" + bnodeS + "_" + o.getURI().replace(':', '_'));
							if (g.isBlankNode())
								g = new BNodeResourceRDFObject("_:b" + bnodeS + "_" + g.getURI().replace(':', '_'));
							w[0].visit(new Quad(s, p, o, g));
						} else w[0].visit(q);
					}

					@Override
					public void setNameSpace(String prefix, String ns) {}

					@Override
					public void setBaseIRI(String baseIRI) {}

					@Override
					public void comment(String comment) {
						w[0].comment(comment);
					}

				});
			} catch (RDFParseException e) {
				long ctime = System.currentTimeMillis();
				System.err.println(String.format("Couldn't read triples from %s (managed to read %,d triples in %s, avg %,d statements/s)", url, count[0], DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
				e.printStackTrace();
			} catch (RDFHandlerException e) {
				long ctime = System.currentTimeMillis();
				System.err.println(String.format("Couldn't read triples from %s (managed to read %,d triples in %s, avg %,d statements/s)", url, count[0], DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
				e.printStackTrace();
			}
			long ctime = System.currentTimeMillis();
			System.out.println(String.format("Read (and possibly wrote) %,d triples from %s in %s, avg %,d statements/s", count[0], url, DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), count[0] * 1000 / (ctime - stime + 1)));
			tcount += count[0];
		}
		long ctime = System.currentTimeMillis();
		System.out.println(String.format("Read a total of %,d triples in %s, avg %,d statements/s. Now closing (and possibly writing) %s", tcount, DurationFormatUtils.formatDuration(ctime - tstime, "H:m:s:S"), tcount * 1000 / (ctime - tstime + 1), outputFile));
		long stime = System.currentTimeMillis();
		w[0].close();
		ctime = System.currentTimeMillis();
		if (splitAt != -1)
			System.out.println(String.format("Closed (and possibly wrote) %s in %s (avg %,d statements/s if writing took place)", outputFile.substring(0, outputFile.indexOf('.')) + "-" + (cursplit[0]) + outputFile.substring(outputFile.indexOf('.')), DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), countSinceLastSplit[0] * 1000 / (ctime - stime + 1)));
		else System.out.println(String.format("Closed (and possibly wrote) %s in %s (avg %,d statements/s if writing took place)", outputFile, DurationFormatUtils.formatDuration(ctime - stime, "H:m:s:S"), tcount * 1000 / (ctime - stime + 1)));
	}
}
