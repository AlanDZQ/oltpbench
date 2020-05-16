/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/


package com.oltpbenchmark.benchmarks.twitter.util;

import java.io.*;
import java.util.ArrayList;

public class TransactionSelector {


    DataInputStream dis = null;
    DataInputStream dis2 = null;

    public TransactionSelector(String filename, String filename2) throws FileNotFoundException {

        if (filename == null || filename.isEmpty()) {
            throw new FileNotFoundException("You must specify a filename to instantiate the TransactionSelector... (probably missing in your workload configuration?)");
        }

        if (filename2 == null || filename2.isEmpty()) {
            throw new FileNotFoundException("You must specify a filename to instantiate the TransactionSelector... (probably missing in your workload configuration?)");
        }


        File file = new File(filename);
        FileInputStream fis = new FileInputStream(file);
        BufferedInputStream  bis = new BufferedInputStream(fis);
        dis = new DataInputStream(bis);
        dis.mark(1024 * 1024 * 1024);

        File file2 = new File(filename2);
        FileInputStream  fis2 = new FileInputStream(file2);
        BufferedInputStream bis2 = new BufferedInputStream(fis2);
        dis2 = new DataInputStream(bis2);
        dis2.mark(1024 * 1024 * 1024);

    }

    private TwitterOperation readNextTransaction() throws IOException {
        String line = dis.readLine();
        String[] sa = line.split("\\s++");
        int tweetid = Integer.parseInt(sa[0]);

        String line2 = dis2.readLine();
        String[] sa2 = line2.split("\\s++");
        int uid = Integer.parseInt(sa2[0]);

        return new TwitterOperation(tweetid, uid);
    }

    public ArrayList<TwitterOperation> readAll() throws IOException {
        ArrayList<TwitterOperation> transactions = new ArrayList<>();

        while (dis.available() > 0 && dis2.available() > 0) {
            transactions.add(readNextTransaction());
        }

        return transactions;
    }

    public void close() throws IOException {
        dis.close();
        dis2.close();
    }

}
